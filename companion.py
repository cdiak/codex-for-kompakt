#!/usr/bin/env python3
"""Personal Kompakt client bridge. Standard library only; Codex stays on the Mac."""
import argparse
import fcntl
import copy
import hmac
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import ssl
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def atomic_json(path, value):
    temp = path.with_suffix('.tmp')
    with open(temp, 'w') as f:
        os.chmod(temp, 0o600)
        json.dump(value, f, ensure_ascii=False)
    os.replace(temp, path)


class Unconfirmed(RuntimeError):
    """Codex did not answer in time; the request may still have taken effect."""


class Rpc:
    def __init__(self, binary, runtime, notification, request, disconnected):
        self.notification, self.request, self.disconnected = notification, request, disconnected
        self.waiting, self.counter = {}, 0
        self.lock = threading.Lock()
        self.closed = False
        self.stderr = open(runtime / 'codex.log', 'a')
        os.chmod(runtime / 'codex.log', 0o600)
        self.process = subprocess.Popen(
            [binary, 'app-server', '--listen', 'stdio://'],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.stderr,
            text=True, bufsize=1)
        threading.Thread(target=self.read, daemon=True).start()
        self.call('initialize', {'clientInfo': {'name': 'kompakt_remote',
            'title': 'Codex for Kompakt', 'version': '0.2.0'}, 'capabilities': {}})
        self.send({'method': 'initialized'})

    def send(self, message):
        with self.lock:
            if self.closed or self.process.poll() is not None:
                raise RuntimeError('Codex has stopped. Restart the Mac companion.')
            self.process.stdin.write(json.dumps(message) + '\n')
            self.process.stdin.flush()

    def call(self, method, params, timeout=45):
        with self.lock:
            self.counter += 1
            key = 'k-' + str(self.counter)
            pending = {'event': threading.Event()}
            self.waiting[key] = pending
        try:
            self.send({'id': key, 'method': method, 'params': params})
            if not pending['event'].wait(timeout):
                raise Unconfirmed('Codex did not confirm the request. Check the task before retrying.')
            message = pending['message']
            if 'error' in message:
                raise RuntimeError(message['error'].get('message', 'Codex request failed'))
            return message.get('result', {})
        finally:
            with self.lock:
                self.waiting.pop(key, None)

    def read(self):
        try:
            for line in self.process.stdout:
                # One malformed line or unexpected event must not end the session.
                try:
                    message = json.loads(line)
                    if not isinstance(message, dict):
                        continue
                    if 'method' in message:
                        if 'id' in message:
                            self.request(message)
                        else:
                            self.notification(message)
                    else:
                        with self.lock:
                            pending = self.waiting.get(message.get('id'))
                            if pending:
                                pending['message'] = message
                                pending['event'].set()
                except Exception as error:
                    print('Ignored a Codex message:', type(error).__name__, flush=True)
        except Exception as error:
            print('Codex connection failed:', type(error).__name__, flush=True)
        finally:
            self.closed = True
            with self.lock:
                for pending in self.waiting.values():
                    pending['message'] = {'error': {'message': 'Codex disconnected'}}
                    pending['event'].set()
            self.disconnected()

    def stop(self):
        self.closed = True
        self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill()
        self.stderr.close()


class Bridge:
    def __init__(self, runtime, workspace, binary, rpc_factory=Rpc):
        self.runtime, self.workspace = Path(runtime), Path(workspace).resolve()
        self.lock = threading.RLock()
        self.operation = threading.Lock()
        self.pending = {}
        self.seen = {}
        self.loaded = False
        self.on_disconnect = None
        self.chats_path = self.runtime / 'chats.json'
        self.chats = json.loads(self.chats_path.read_text()) if self.chats_path.exists() else {}
        try:
            hostname = subprocess.check_output(['/usr/sbin/scutil', '--get', 'ComputerName'], text=True).strip()
        except (OSError, subprocess.CalledProcessError):
            hostname = socket.gethostname().split('.')[0]
        self.host = {'id': socket.gethostname(), 'name': hostname, 'kind': 'computer'}
        self.state = {'revision': 0, 'status': 'Connecting', 'busy': False,
                      'threadId': None, 'turnId': None, 'messages': [],
                      'diff': '', 'approvals': [], 'project': self.workspace.name,
                      'workspace': str(self.workspace), 'account': 'Checking sign-in',
                      'title': 'New chat', 'createdAt': time.time(), 'updatedAt': time.time()}
        path = self.runtime / 'state.json'
        if path.exists():
            previous = json.loads(path.read_text())
            if previous.get('workspace') != str(self.workspace):
                raise RuntimeError('This runtime belongs to a different project. Use a separate runtime directory.')
            self.seen = previous.pop('_seen', {})
            self.state.update(previous)
            if self.state.get('title', 'New chat') == 'New chat' and self.state.get('threadId'):
                prompts = [m['text'] for m in self.state['messages'] if m['role'] == 'you'
                           and m['text'] not in ('Approved this action', 'Denied this action', 'Answered question')]
                self.state['title'] = (prompts[-1][:72] if prompts else 'Previous chat')
            if self.state['busy']:
                self.state['messages'].append({'id': 'recovery-' + secrets.token_hex(4),
                    'role': 'system', 'text': 'The companion restarted. The previous task was interrupted; no action was replayed.'})
        self.state.update(busy=False, turnId=None, approvals=[], status='Connecting')
        self.rpc = rpc_factory(binary, self.runtime, self.notify, self.request, self.disconnected)
        account = self.rpc.call('account/read', {'refreshToken': False}).get('account') or {}
        if account.get('type') not in ('chatgpt', 'apiKey'):
            raise RuntimeError('Sign in to Codex on this Mac before starting the companion.')
        self.state.update(status='Ready', account='ChatGPT account' if account['type'] == 'chatgpt' else 'API account')
        self.save()

    def save(self):
        self.state['revision'] += 1
        self.state['messages'] = self.state['messages'][-100:]
        self.state['diff'] = self.state['diff'][-200000:]
        self.state['approvals'] = [copy.deepcopy(v['public']) for v in self.pending.values()]
        self.state['host'] = self.host
        if self.state.get('threadId'):
            fields = ('threadId', 'title', 'createdAt', 'updatedAt', 'messages', 'diff', 'status')
            self.chats[self.state['threadId']] = {k: copy.deepcopy(self.state.get(k)) for k in fields}
        atomic_json(self.chats_path, self.chats)
        data = copy.deepcopy(self.state)
        for key in list(self.seen)[:-500]:
            del self.seen[key]
        data['_seen'] = self.seen
        atomic_json(self.runtime / 'state.json', data)

    def snapshot(self):
        with self.lock:
            return copy.deepcopy(self.state)

    def chat_list(self):
        with self.lock:
            summaries = [{'id': c['threadId'], 'title': c.get('title') or 'Untitled chat',
                          'updatedAt': c.get('updatedAt') or 0,
                          'selected': c['threadId'] == self.state['threadId']}
                         for c in self.chats.values()]
            summaries.sort(key=lambda c: c['updatedAt'], reverse=True)
            return {'hosts': [{'host': self.host, 'chats': summaries}], 'busy': self.state['busy']}

    def select_chat(self, body):
        with self.operation, self.lock:
            if self.state['busy']:
                raise ValueError('Finish or stop the current task before switching chats.')
            key = body.get('id')
            if key is not None and key not in self.chats:
                raise ValueError('This chat is not available on the paired computer.')
            self.pending.clear()
            if key is None:
                self.state.update(threadId=None, title='New chat', messages=[], diff='',
                                  createdAt=time.time(), updatedAt=time.time())
            else:
                self.state.update(copy.deepcopy(self.chats[key]))
            self.state.update(status='Ready', busy=False, turnId=None, approvals=[])
            self.loaded = False
            self.save()
            return {'selected': self.state['threadId']}

    def rename_chat(self, body):
        title = body.get('title', '').strip()
        if not title or len(title) > 100:
            raise ValueError('Use a chat name between 1 and 100 characters.')
        with self.lock:
            key = body.get('id')
            if key not in self.chats:
                raise ValueError('Chat not found.')
            self.chats[key]['title'] = title
            if self.state['threadId'] == key:
                self.state['title'] = title
            self.save()
        return {'renamed': True}

    def message(self, key, role, text, append=False):
        for item in self.state['messages']:
            if item['id'] == key:
                item['text'] = ((item['text'] if append else '') + text)[-30000:]
                return
        self.state['messages'].append({'id': key, 'role': role, 'text': text[-30000:]})

    def disconnected(self):
        with self.lock:
            self.pending.clear()
            self.state.update(status='Codex disconnected', busy=False, turnId=None)
            self.save()
        if self.on_disconnect:
            self.on_disconnect()

    def notify(self, event):
        method, p = event['method'], event.get('params') or {}
        with self.lock:
            if p.get('threadId') and p['threadId'] != self.state['threadId']:
                return
            if method == 'turn/started':
                self.state.update(turnId=p['turn']['id'], busy=True, status='Working')
            elif method == 'turn/completed':
                turn = p['turn']
                self.pending.clear()
                self.state.update(busy=False, turnId=None,
                    status={'completed': 'Completed', 'interrupted': 'Interrupted', 'failed': 'Failed'}.get(turn.get('status'), 'Finished'))
                if turn.get('error'):
                    self.message('error-' + turn['id'], 'system', str(turn['error'].get('message', turn['error'])))
            elif method == 'turn/diff/updated':
                self.state['diff'] = p.get('diff', '')
            elif method == 'item/agentMessage/delta':
                self.message(p['itemId'], 'assistant', p.get('delta', ''), append=True)
            elif method in ('item/started', 'item/completed'):
                item = p.get('item', {})
                kind, key = item.get('type'), item.get('id', secrets.token_hex(4))
                if kind == 'agentMessage' and item.get('text'):
                    self.message(key, 'assistant', item['text'])
                elif kind == 'commandExecution':
                    text = item.get('command', '')
                    if item.get('aggregatedOutput'):
                        text += '\n' + item['aggregatedOutput'][-12000:]
                    if item.get('exitCode') is not None:
                        text += '\nExit code: ' + str(item['exitCode'])
                    self.message(key, 'command', text)
                elif kind == 'fileChange':
                    changes = item.get('changes', [])
                    text = '\n\n'.join(str(c.get('path', '')) + '\n' + c.get('diff', '') for c in changes)
                    self.message(key, 'changes', text)
                elif kind == 'plan':
                    self.message(key, 'plan', item.get('text', ''))
                else:
                    return
            elif method == 'error':
                self.message('error-' + secrets.token_hex(4), 'system', p.get('error', {}).get('message', 'Codex reported an error'))
            elif method == 'serverRequest/resolved':
                rid = p.get('requestId')
                for key in list(self.pending):
                    if self.pending[key]['rpcId'] == rid:
                        del self.pending[key]
            else:
                return
            self.save()

    def request(self, message):
        method, p = message['method'], message.get('params') or {}
        allowed = ('item/commandExecution/requestApproval', 'item/fileChange/requestApproval',
                   'item/permissions/requestApproval', 'item/tool/requestUserInput')
        if method not in allowed:
            self.rpc.send({'id': message['id'], 'error': {'code': -32601,
                'message': 'This client does not support this request. No action was approved.'}})
            return
        with self.lock:
            key = secrets.token_hex(16)
            item_text = next((m['text'] for m in self.state['messages'] if m['id'] == p.get('itemId')), '')
            self.pending[key] = {'rpcId': message['id'], 'method': method, 'params': p,
                'public': {'id': key, 'kind': method, 'details': p, 'itemText': item_text}}
            self.state['status'] = 'Question' if method.endswith('requestUserInput') else 'Approval needed'
            self.save()

    def task(self, body):
        prompt, request_id = body.get('prompt', '').strip(), body.get('requestId', '')
        if not prompt or len(prompt) > 16000 or not isinstance(request_id, str) or not 8 <= len(request_id) <= 100:
            raise ValueError('A prompt and a unique request ID are required.')
        with self.operation:
            with self.lock:
                if request_id in self.seen:
                    return {'accepted': True, 'duplicate': True}
                if self.state['busy']:
                    raise ValueError('A task is running. Wait or stop it before sending another.')
                if body.get('newConversation'):
                    self.state.update(threadId=None, title='New chat', messages=[], diff='', createdAt=time.time())
                self.seen[request_id] = 'submitted'
                self.state.update(busy=True, status='Starting', diff='', updatedAt=time.time())
                if not self.state.get('threadId'):
                    self.state['title'] = prompt.replace('\n', ' ')[:72]
                self.message('user-' + request_id, 'you', prompt)
                self.save()
            try:
                options = {'cwd': str(self.workspace), 'sandbox': 'read-only',
                    'approvalPolicy': 'on-request', 'approvalsReviewer': 'user'}
                if body.get('newConversation') or not self.state['threadId']:
                    result = self.rpc.call('thread/start', dict(options,
                        developerInstructions='You are assisting the user through a small e-ink phone. Keep progress concise. Work in the selected project. Request approval for changes under the configured policy. Do not delegate to subagents.'))
                    with self.lock:
                        self.state['threadId'] = result['thread']['id']
                        self.save()
                    self.loaded = True
                elif not self.loaded:
                    self.rpc.call('thread/resume', dict(options, threadId=self.state['threadId']))
                    self.loaded = True
                self.rpc.call('turn/start', {'threadId': self.state['threadId'],
                    'input': [{'type': 'text', 'text': prompt}],
                    'approvalPolicy': 'on-request', 'approvalsReviewer': 'user'})
                return {'accepted': True}
            except Exception as error:
                with self.lock:
                    # A definite failure may be retried with the same request ID.
                    # An unconfirmed request may have started, so it stays recorded.
                    if not isinstance(error, Unconfirmed):
                        self.seen.pop(request_id, None)
                    self.state.update(busy=False, status='Request failed')
                    self.message('failure-' + request_id, 'system', str(error))
                    self.save()
                raise

    def approve(self, body):
        key, decision = body.get('id'), body.get('decision')
        if decision not in ('accept', 'decline', 'answer'):
            raise ValueError('Choose approve, deny, or answer.')
        with self.lock:
            entry = self.pending.get(key)
            if not entry:
                raise ValueError('This request has expired or was already answered.')
            method, params = entry['method'], entry['params']
            if method.endswith('requestUserInput'):
                if decision != 'answer':
                    raise ValueError('This request requires an answer.')
                answers = body.get('answers', {})
                expected = {q['id'] for q in params['questions']}
                if set(answers) != expected or any(not isinstance(a, str) or len(a) > 4000 for a in answers.values()):
                    raise ValueError('Answer each question.')
                result = {'answers': {k: {'answers': [v]} for k, v in answers.items()}}
            elif decision == 'answer':
                raise ValueError('This request requires approve or deny.')
            elif method.endswith('permissions/requestApproval'):
                result = {'permissions': params.get('permissions', {}) if decision == 'accept' else {}, 'scope': 'turn'}
            else:
                result = {'decision': decision}
            self.rpc.send({'id': entry['rpcId'], 'result': result})
            del self.pending[key]
            self.message('decision-' + key, 'you', 'Answered question' if decision == 'answer' else ('Approved this action' if decision == 'accept' else 'Denied this action'))
            self.state['status'] = 'Working' if not self.pending else 'Approval needed'
            self.save()
            return {'accepted': True}

    def interrupt(self):
        with self.lock:
            thread, turn = self.state['threadId'], self.state['turnId']
        if not turn:
            raise ValueError('There is no active turn to stop.')
        self.rpc.call('turn/interrupt', {'threadId': thread, 'turnId': turn})
        return {'accepted': True}


def handler(bridge, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def reply(self, status, value):
            data = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def authorized(self):
            return not self.headers.get('Origin') and hmac.compare_digest(
                self.headers.get('Authorization', '').encode(), ('Bearer ' + token).encode())

        def do_GET(self):
            if not self.authorized():
                return self.reply(401, {'error': 'Pair this device with the Mac.'})
            if self.path == '/state':
                self.reply(200, bridge.snapshot())
            elif self.path == '/chats':
                self.reply(200, bridge.chat_list())
            else:
                self.reply(404, {'error': 'Not found'})

        def do_POST(self):
            if not self.authorized():
                return self.reply(401, {'error': 'Pair this device with the Mac.'})
            try:
                if self.headers.get_content_type() != 'application/json':
                    raise ValueError('JSON required')
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length <= 65536:
                    raise ValueError('Invalid request length')
                body = json.loads(self.rfile.read(length))
                if not isinstance(body, dict):
                    raise ValueError('JSON object required')
                if self.path == '/task':
                    result = bridge.task(body)
                elif self.path == '/approval':
                    result = bridge.approve(body)
                elif self.path == '/interrupt':
                    result = bridge.interrupt()
                elif self.path == '/chat/select':
                    result = bridge.select_chat(body)
                elif self.path == '/chat/rename':
                    result = bridge.rename_chat(body)
                else:
                    return self.reply(404, {'error': 'Not found'})
                self.reply(200, result)
            except (ValueError, KeyError, TypeError) as error:
                self.reply(400, {'error': str(error)})
            except Exception as error:
                self.reply(503, {'error': str(error)})
    return Handler


class Server(ThreadingHTTPServer):
    """HTTPS server that runs each TLS handshake in the connection's own thread.

    Wrapping the listening socket would run handshakes in the accept loop, where
    one silent client blocks every other request.
    """
    daemon_threads = True

    def __init__(self, address, handler_class, context, timeout=60):
        self.context, self.client_timeout = context, timeout
        super().__init__(address, handler_class)

    def finish_request(self, request, client_address):
        request.settimeout(self.client_timeout)
        try:
            secure = self.context.wrap_socket(request, server_side=True)
        except OSError:
            return
        try:
            super().finish_request(secure, client_address)
        except OSError:
            pass
        finally:
            secure.close()


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--runtime', type=Path, required=True)
    p.add_argument('--workspace', type=Path, required=True)
    p.add_argument('--bind', default='127.0.0.1')
    p.add_argument('--port', type=int, default=39443)
    p.add_argument('--codex', default=shutil.which('codex'))
    args = p.parse_args()
    os.umask(0o077)
    args.runtime.mkdir(parents=True, exist_ok=True)
    args.workspace.mkdir(parents=True, exist_ok=True)
    lock_file = open(args.runtime / 'companion.lock', 'a')
    try:
        fcntl.flock(lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit('The companion is already running for this project.')
    token_path = args.runtime / 'token'
    if not token_path.exists():
        token_path.write_text(secrets.token_urlsafe(32))
    token = token_path.read_text().strip()
    if not args.codex:
        raise SystemExit('Codex is not installed.')
    bridge = Bridge(args.runtime, args.workspace, args.codex)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.runtime / 'server.crt', args.runtime / 'server.key')
    server = Server((args.bind, args.port), handler(bridge, token), context)
    stopping, lost = threading.Event(), threading.Event()
    def stop(*_):
        stopping.set()
        threading.Thread(target=server.shutdown, daemon=True).start()
    def codex_lost():
        # Exit so that a login service restarts the companion with a new Codex.
        if not stopping.is_set():
            lost.set()
            stop()
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    bridge.on_disconnect = codex_lost
    if bridge.rpc.closed:
        codex_lost()
    print('Kompakt companion ready on HTTPS port', args.port, flush=True)
    (args.runtime / 'companion.pid').write_text(str(os.getpid()))
    try:
        server.serve_forever()
    finally:
        stopping.set()
        server.server_close()
        bridge.rpc.stop()
        (args.runtime / 'companion.pid').unlink(missing_ok=True)
    if lost.is_set():
        raise SystemExit('Codex stopped. Start the companion again.')


if __name__ == '__main__':
    main()
