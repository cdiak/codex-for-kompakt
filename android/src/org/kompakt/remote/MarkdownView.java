package org.kompakt.remote;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;

/** CommonMark parsed to native views. No HTML, scripts, remote images, or WebView. */
public final class MarkdownView extends LinearLayout {
    private static final Parser PARSER = Parser.builder().build();
    public MarkdownView(Context context, String markdown) {
        super(context); setOrientation(VERTICAL);
        blocks(this, PARSER.parse(markdown), 0);
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(CharSequence s, int size) {
        TextView t = new TextView(getContext()); t.setTextColor(Color.BLACK); t.setTextSize(size);
        t.setText(s); t.setLineSpacing(dp(3), 1); t.setTextIsSelectable(true); return t;
    }
    private void addText(LinearLayout box, Node n, int size, String prefix) {
        SpannableStringBuilder s = new SpannableStringBuilder(prefix);
        inline(n, s);
        TextView t = label(s, size);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(9)); box.addView(t, p);
    }
    private void blocks(LinearLayout box, Node parent, int depth) {
        if (depth > 20) return;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            if (n instanceof FencedCodeBlock) {
                FencedCodeBlock c = (FencedCodeBlock)n; code(box, c.getLiteral(), c.getInfo());
            } else if (n instanceof IndentedCodeBlock) {
                code(box, ((IndentedCodeBlock)n).getLiteral(), "code");
            } else if (n instanceof Heading) {
                SpannableStringBuilder s = new SpannableStringBuilder(); inline(n, s);
                TextView t = label(s, ((Heading)n).getLevel() <= 2 ? 21 : 18); t.setTypeface(null, Typeface.BOLD);
                t.setPadding(0, dp(8), 0, dp(8)); box.addView(t);
            } else if (n instanceof Paragraph) {
                addText(box, n, 17, "");
            } else if (n instanceof BulletList || n instanceof OrderedList) {
                int number = n instanceof OrderedList ? ((OrderedList)n).getStartNumber() : 0;
                for (Node item = n.getFirstChild(); item != null; item = item.getNext()) {
                    LinearLayout row = new LinearLayout(getContext()); row.setOrientation(HORIZONTAL);
                    row.setPadding(dp(2), 0, 0, 0);
                    TextView marker = label(number == 0 ? "•" : (number++)+".", 17);
                    row.addView(marker, new LinearLayout.LayoutParams(dp(25), -2));
                    LinearLayout body = new LinearLayout(getContext()); body.setOrientation(VERTICAL);
                    row.addView(body, new LinearLayout.LayoutParams(0, -2, 1)); blocks(body, item, depth + 1);
                    box.addView(row);
                }
            } else if (n instanceof BlockQuote) {
                LinearLayout quote = new LinearLayout(getContext()); quote.setOrientation(VERTICAL);
                quote.setPadding(dp(12), dp(6), dp(8), dp(4)); quote.setBackgroundColor(0xfff3f3f3);
                blocks(quote, n, depth + 1); box.addView(quote);
            } else if (n instanceof ThematicBreak) {
                View line = new View(getContext()); line.setBackgroundColor(Color.GRAY);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(1)); p.setMargins(0, dp(12), 0, dp(12)); box.addView(line, p);
            } else if (n instanceof HtmlBlock) {
                addText(box, new Text(((HtmlBlock)n).getLiteral()), 17, "");
            } else {
                blocks(box, n, depth + 1);
            }
        }
    }
    private void inline(Node parent, SpannableStringBuilder s) {
        if (parent instanceof Text) { s.append(((Text)parent).getLiteral()); return; }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNext()) {
            int start = s.length();
            if (n instanceof Text) s.append(((Text)n).getLiteral());
            else if (n instanceof Code) {
                s.append(((Code)n).getLiteral());
                s.setSpan(new TypefaceSpan("monospace"), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new BackgroundColorSpan(0xffeeeeee), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (n instanceof SoftLineBreak) s.append(' ');
            else if (n instanceof HardLineBreak) s.append('\n');
            else if (n instanceof HtmlInline) s.append(((HtmlInline)n).getLiteral());
            else {
                inline(n, s);
                if (n instanceof StrongEmphasis) s.setSpan(new StyleSpan(Typeface.BOLD), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (n instanceof Emphasis) s.setSpan(new StyleSpan(Typeface.ITALIC), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (n instanceof Link) {
                    s.setSpan(new UnderlineSpan(), start, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    String url = ((Link)n).getDestination();
                    if (!url.equals(s.subSequence(start, s.length()).toString())) s.append(" ("+url+")");
                }
            }
        }
    }
    private void code(LinearLayout box, String literal, String info) {
        LinearLayout card = new LinearLayout(getContext()); card.setOrientation(VERTICAL);
        GradientDrawable border = new GradientDrawable(); border.setColor(0xfff4f4f4); border.setCornerRadius(dp(12)); border.setStroke(dp(1), 0xffcccccc); card.setBackground(border);
        LinearLayout header = new LinearLayout(getContext()); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(12), 0, dp(4), 0);
        TextView language = label(info == null || info.trim().isEmpty() ? "code" : info.trim().split("\\s+", 2)[0], 12);
        language.setTextColor(Color.DKGRAY); header.addView(language, new LinearLayout.LayoutParams(0, dp(40), 1)); language.setGravity(Gravity.CENTER_VERTICAL);
        Button copy = new Button(getContext()); copy.setText("Copy"); copy.setAllCaps(false); copy.setTextSize(12); copy.setTextColor(Color.BLACK); copy.setBackgroundColor(Color.TRANSPARENT);
        copy.setOnClickListener(v -> {
            ((ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Code", literal));
            Toast.makeText(getContext(), "Code copied", Toast.LENGTH_SHORT).show();
        });
        header.addView(copy, new LinearLayout.LayoutParams(dp(64), dp(40))); card.addView(header);
        HorizontalScrollView sc = new HorizontalScrollView(getContext()); sc.setFillViewport(true); sc.setSmoothScrollingEnabled(false);
        TextView code = label(literal.replaceFirst("\\n$", ""), 15); code.setTypeface(Typeface.MONOSPACE);
        code.setPadding(dp(12), dp(2), dp(12), dp(12)); sc.addView(code); card.addView(sc);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.setMargins(0, dp(4), 0, dp(12)); box.addView(card, p);
    }
}
