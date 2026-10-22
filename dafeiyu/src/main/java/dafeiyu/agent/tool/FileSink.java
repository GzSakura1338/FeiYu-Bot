package dafeiyu.agent.tool;

import java.nio.file.Path;

@FunctionalInterface
public interface FileSink {

    String send(Path file, String name) throws ToolFailure;
}
