import json
from pathlib import Path
import tempfile
import unittest
from companion import Bridge


class FakeRpc:
    def __init__(self, binary, runtime, notify, request, disconnected):
        self.sent, self.calls = [], []
        self.notify = notify
        self.counter = 0
    def call(self, method, params, timeout=45):
        self.calls.append((method, params))
        if method == 'account/read': return {'account': {'type': 'chatgpt'}}
        if method == 'thread/start':
            self.counter += 1
            return {'thread': {'id': 'thread-'+str(self.counter)}}
        if method == 'turn/start':
            self.notify({'method':'turn/started','params':{'threadId':params['threadId'],'turn':{'id':'turn-1'}}})
        return {}
    def send(self, message): self.sent.append(message)


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name)
        self.bridge = Bridge(self.path, self.path/'project', 'fake', FakeRpc)
    def tearDown(self): self.temp.cleanup()
    def pending(self):
        self.bridge.request({'id':77,'method':'item/commandExecution/requestApproval',
            'params':{'command':'python3 hello.py','threadId':'thread-1','turnId':'turn-1'}})
        return self.bridge.snapshot()['approvals'][0]['id']
    def test_duplicate_task_is_not_reexecuted(self):
        body = {'prompt':'make hello.py','requestId':'test-0001'}
        self.bridge.task(body)
        self.assertTrue(self.bridge.task(body)['duplicate'])
        self.assertEqual(sum(m=='turn/start' for m,p in self.bridge.rpc.calls),1)
    def test_another_task_cannot_start_while_busy(self):
        self.bridge.task({'prompt':'first','requestId':'test-0001'})
        with self.assertRaises(ValueError):
            self.bridge.task({'prompt':'second','requestId':'test-0002'})
    def test_approval_is_explicit_and_single_use(self):
        key = self.pending()
        self.assertEqual(self.bridge.rpc.sent, [])
        self.bridge.approve({'id':key,'decision':'accept'})
        self.assertEqual(self.bridge.rpc.sent,[{'id':77,'result':{'decision':'accept'}}])
        with self.assertRaises(ValueError): self.bridge.approve({'id':key,'decision':'accept'})
    def test_deny_is_forwarded_as_deny(self):
        self.bridge.approve({'id':self.pending(),'decision':'decline'})
        self.assertEqual(self.bridge.rpc.sent[-1]['result']['decision'],'decline')
    def test_completed_turn_invalidates_pending_approval(self):
        key = self.pending()
        self.bridge.notify({'method':'turn/completed','params':{'turn':{'id':'turn-1','status':'completed'}}})
        with self.assertRaises(ValueError): self.bridge.approve({'id':key,'decision':'accept'})
    def test_restart_never_replays_task_or_approval(self):
        body={'prompt':'task','requestId':'test-0001'}
        self.bridge.task(body); key=self.pending()
        recovered = Bridge(self.path,self.path/'project','fake',FakeRpc)
        self.assertFalse(recovered.snapshot()['busy'])
        self.assertEqual(recovered.snapshot()['approvals'],[])
        self.assertTrue(recovered.task(body)['duplicate'])
        self.assertFalse(any(m=='turn/start' for m,p in recovered.rpc.calls))
        with self.assertRaises(ValueError): recovered.approve({'id':key,'decision':'accept'})
    def test_unknown_request_is_not_approved(self):
        self.bridge.request({'id':1,'method':'new/unknown','params':{}})
        self.assertIn('error',self.bridge.rpc.sent[-1])
    def test_permission_denial_grants_nothing(self):
        self.bridge.request({'id':2,'method':'item/permissions/requestApproval',
            'params':{'permissions':{'network':{'enabled':True}}}})
        key=self.bridge.snapshot()['approvals'][0]['id']
        self.bridge.approve({'id':key,'decision':'decline'})
        self.assertEqual(self.bridge.rpc.sent[-1]['result']['permissions'],{})
    def finish(self):
        self.bridge.notify({'method':'turn/completed','params':{'turn':{'id':'turn-1','status':'completed'}}})
    def test_new_chat_keeps_old_history_and_can_resume(self):
        self.bridge.task({'prompt':'first project task','requestId':'first-task'})
        self.finish()
        first=self.bridge.snapshot()['threadId']
        self.bridge.select_chat({'id':None})
        self.assertEqual(self.bridge.snapshot()['messages'],[])
        self.bridge.task({'prompt':'second project task','requestId':'second-task'})
        self.finish()
        self.assertEqual(len(self.bridge.chat_list()['hosts'][0]['chats']),2)
        self.bridge.select_chat({'id':first})
        self.assertEqual(self.bridge.snapshot()['messages'][0]['text'],'first project task')
        self.bridge.task({'prompt':'continue first','requestId':'continue-task'})
        self.assertTrue(any(m=='thread/resume' and p['threadId']==first for m,p in self.bridge.rpc.calls))
    def test_chat_history_survives_restart(self):
        self.bridge.task({'prompt':'keep this chat','requestId':'keep-task'})
        self.finish(); key=self.bridge.snapshot()['threadId']
        self.bridge.select_chat({'id':None})
        recovered=Bridge(self.path,self.path/'project','fake',FakeRpc)
        self.assertEqual(len(recovered.chat_list()['hosts'][0]['chats']),1)
        recovered.select_chat({'id':key})
        self.assertEqual(recovered.snapshot()['messages'][0]['text'],'keep this chat')
    def test_unknown_chat_is_rejected(self):
        with self.assertRaises(ValueError): self.bridge.select_chat({'id':'untracked-desktop-thread'})
    def test_switching_busy_chat_preserves_pending_approval(self):
        self.bridge.task({'prompt':'work','requestId':'work-task'})
        key=self.pending()
        with self.assertRaises(ValueError): self.bridge.select_chat({'id':None})
        self.assertEqual(self.bridge.snapshot()['approvals'][0]['id'],key)
    def test_rename_survives_save_and_restart(self):
        self.bridge.task({'prompt':'original prompt','requestId':'rename-task'})
        self.finish(); key=self.bridge.snapshot()['threadId']
        self.bridge.rename_chat({'id':key,'title':'A useful name'})
        recovered=Bridge(self.path,self.path/'project','fake',FakeRpc)
        self.assertEqual(recovered.chat_list()['hosts'][0]['chats'][0]['title'],'A useful name')
    def test_rename_from_empty_chat_survives_restart(self):
        self.bridge.task({'prompt':'original prompt','requestId':'rename-task'})
        self.finish(); key=self.bridge.snapshot()['threadId']
        self.bridge.select_chat({'id':None})
        self.bridge.rename_chat({'id':key,'title':'Renamed from new chat'})
        recovered=Bridge(self.path,self.path/'project','fake',FakeRpc)
        self.assertEqual(recovered.chat_list()['hosts'][0]['chats'][0]['title'],'Renamed from new chat')


if __name__ == '__main__': unittest.main()
