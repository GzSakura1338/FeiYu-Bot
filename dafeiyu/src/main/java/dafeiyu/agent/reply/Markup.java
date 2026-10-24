package dafeiyu.agent.reply;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Markup(String thought, String said, List<Note> notes, List<String> errands, String poke, List<String> promises,
                     List<String> done) {

    public record Note(String about, String text) {}

    public static final String THOUGHT_OPEN = "<内心>";
    public static final String THOUGHT_CLOSE = "</内心>";
    private static final Pattern NOTE = Pattern.compile("<记住(?:\\s+关于\\s*=\\s*[\"'“”]?([^\"'“”>]*)[\"'“”]?)?\\s*>(.*?)(?:</记住>|$)", Pattern.DOTALL);
    private static final Pattern ERRAND = Pattern.compile("<去做>(.*?)(?:</去做>|$)", Pattern.DOTALL);
    private static final Pattern PROMISE = Pattern.compile("<承诺>(.*?)(?:</承诺>|$)", Pattern.DOTALL);
    private static final Pattern DONE = Pattern.compile("<完成>(.*?)(?:</完成>|$)", Pattern.DOTALL);
    private static final Pattern POKE = Pattern.compile("<拍一拍\\s*/>|<拍一拍>(.*?)(?:</拍一拍>|$)", Pattern.DOTALL);
    private static final Pattern STRAY = Pattern.compile("</?(?:记住|去做|承诺|完成|拍一拍)[^>\\n]{0,40}>");

    public Markup {
        thought = thought == null ? "" : thought;
        said = said == null ? "" : said;
        notes = notes == null ? List.of() : List.copyOf(notes);
        errands = errands == null ? List.of() : List.copyOf(errands);
        promises = promises == null ? List.of() : List.copyOf(promises);
        done = done == null ? List.of() : List.copyOf(done);
    }

    public Markup(String thought, String said, List<Note> notes, List<String> errands, String poke) {
        this(thought, said, notes, errands, poke, List.of(), List.of());
    }

    public static Markup plain(String text) {
        return new Markup("", text, List.of(), List.of(), null);
    }

    private static List<String> collect(Pattern pattern, String closing, String text) {
        List<String> found = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String body = m.group(1).replace(closing, "").strip();
            if (!body.isEmpty()) {
                found.add(body);
            }
        }
        return found;
    }

    private static final Pattern TAG = Pattern.compile("[<＜]\\s*(/?)\\s*(內心|内心|記住|记住|承諾|承诺|去做|完成|拍一拍)([^>＞]*)[>＞]");
    private static final Pattern THOUGHT_ALIAS = Pattern.compile("[<＜]\\s*(/?)\\s*(?!记住|記住|去做|承诺|承諾|完成|拍一拍)[^<>＜＞\\n/]{0,6}"
            + "(?:内心|內心|心里|心裡|心声|心聲|嘀咕|独白|獨白)[^<>＜＞\\n]{0,10}[>＞]");
    private static final Pattern THOUGHT_LINE = Pattern.compile("(?m)^[ \\t]*(?:[【\\[（(][ \\t]*(?:内心|內心)[^】\\]）)\\n]{0,4}[】\\]）)][ \\t]*[:：]?"
            + "|(?:内心|內心)[^:：\\n]{0,4}[:：])(.*)$");
    private static final Map<String, String> SIMPLIFIED = Map.of("內心", "内心", "記住", "记住", "承諾", "承诺");

    static String normalize(String text) {
        String aliased = THOUGHT_ALIAS.matcher(text).replaceAll(m -> Matcher.quoteReplacement("<" + m.group(1) + "内心>"));
        aliased = THOUGHT_LINE.matcher(aliased).replaceAll(m -> Matcher.quoteReplacement(THOUGHT_OPEN + m.group(1).strip() + THOUGHT_CLOSE));
        return TAG.matcher(aliased).replaceAll(m -> Matcher.quoteReplacement(
                "<" + m.group(1) + SIMPLIFIED.getOrDefault(m.group(2), m.group(2)) + m.group(3).stripTrailing() + ">"));
    }

    public static Markup parse(String raw) {
        String text = normalize(raw == null ? "" : raw);
        List<String> errands = collect(ERRAND, "</去做>", text);
        text = ERRAND.matcher(text).replaceAll("");
        List<String> promises = collect(PROMISE, "</承诺>", text);
        text = PROMISE.matcher(text).replaceAll("");
        List<String> done = collect(DONE, "</完成>", text);
        text = DONE.matcher(text).replaceAll("");
        String poke = null;
        Matcher pokeTag = POKE.matcher(text);
        if (pokeTag.find()) {
            poke = pokeTag.group(1) == null ? "" : pokeTag.group(1).replace("</拍一拍>", "").strip();
        }
        text = POKE.matcher(text).replaceAll("");
        List<Note> notes = new ArrayList<>();
        Matcher note = NOTE.matcher(text);
        StringBuilder rest = new StringBuilder();
        while (note.find()) {
            String body = note.group(2).replace("</记住>", "").strip();
            if (!body.isEmpty()) {
                notes.add(new Note(note.group(1) == null ? "" : note.group(1).strip(), body));
            }
            note.appendReplacement(rest, "");
        }
        note.appendTail(rest);
        text = STRAY.matcher(rest.toString()).replaceAll("");
        List<String> thoughts = new ArrayList<>();
        StringBuilder said = new StringBuilder();
        int at = 0;
        while (at < text.length()) {
            int open = text.indexOf(THOUGHT_OPEN, at);
            int close = text.indexOf(THOUGHT_CLOSE, at);
            if (close >= 0 && (open < 0 || close < open)) {
                int from = Math.max(at, text.lastIndexOf('\n', close) + 1);
                said.append(text, at, from);
                thoughts.add(text.substring(from, close));
                at = close + THOUGHT_CLOSE.length();
                continue;
            }
            if (open < 0) {
                said.append(text, at, text.length());
                break;
            }
            said.append(text, at, open);
            int start = open + THOUGHT_OPEN.length();
            int closing = text.indexOf(THOUGHT_CLOSE, start);
            int next = text.indexOf(THOUGHT_OPEN, start);
            if (closing >= 0 && (next < 0 || closing < next)) {
                thoughts.add(text.substring(start, closing));
                at = closing + THOUGHT_CLOSE.length();
            } else {
                int newline = text.indexOf('\n', start);
                int end = newline < 0 ? text.length() : newline;
                if (next >= 0 && next < end) {
                    end = next;
                }
                thoughts.add(text.substring(start, end));
                at = end;
            }
        }
        String thought = String.join(" ", thoughts.stream().map(String::strip).filter(t -> !t.isEmpty()).toList());
        return new Markup(thought, said.toString().strip(), notes, errands, poke, promises, done);
    }

    public static String saidOf(String raw) {
        return parse(raw).said();
    }

    public String render() {
        StringBuilder out = new StringBuilder(thought.isEmpty() ? "" : THOUGHT_OPEN + thought + THOUGHT_CLOSE).append(said);
        for (Note note : notes) {
            out.append("<记住").append(note.about().isEmpty() ? "" : " 关于=\"" + note.about() + "\"").append('>')
                    .append(note.text()).append("</记住>");
        }
        if (poke != null) {
            out.append("<拍一拍>").append(poke).append("</拍一拍>");
        }
        for (String task : errands) {
            out.append("<去做>").append(task).append("</去做>");
        }
        for (String promise : promises) {
            out.append("<承诺>").append(promise).append("</承诺>");
        }
        for (String finished : done) {
            out.append("<完成>").append(finished).append("</完成>");
        }
        return out.toString();
    }
}
