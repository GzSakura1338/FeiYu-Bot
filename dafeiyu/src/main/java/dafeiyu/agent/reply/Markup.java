package dafeiyu.agent.reply;

import java.util.ArrayList;
import java.util.List;
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

    public static Markup parse(String raw) {
        String text = raw == null ? "" : raw;
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
        text = rest.toString().replace("</记住>", "");
        int open = text.indexOf(THOUGHT_OPEN);
        if (open < 0) {
            return new Markup("", text.replace(THOUGHT_CLOSE, "").strip(), notes, errands, poke, promises, done);
        }
        int close = text.indexOf(THOUGHT_CLOSE, open);
        int end;
        int resume;
        if (close >= 0) {
            end = close;
            resume = close + THOUGHT_CLOSE.length();
        } else {
            int newline = text.indexOf('\n', open);
            end = newline < 0 ? text.length() : newline;
            resume = end;
        }
        String thought = text.substring(open + THOUGHT_OPEN.length(), end).strip();
        String said = (text.substring(0, open) + text.substring(resume)).replace(THOUGHT_OPEN, "").replace(THOUGHT_CLOSE, "").strip();
        return new Markup(thought, said, notes, errands, poke, promises, done);
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
