package dafeiyu.agent;

public record ImagePart(String mime, String base64) {

    public String dataUrl() {
        return "data:" + mime + ";base64," + base64;
    }
}
