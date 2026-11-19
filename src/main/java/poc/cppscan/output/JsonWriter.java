package poc.cppscan.output;

import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes JSON in the reference file's style: Jackson's DefaultPrettyPrinter ("key" : value, two-space indent,
 * "[ {" for arrays of objects, "[ ]" when empty), the platform line separator, no trailing newline.
 */
public class JsonWriter {

    private static final ObjectWriter WRITER = new ObjectMapper().writer(new DefaultPrettyPrinter());

    public static String toJson(Object value) throws IOException {
        return WRITER.writeValueAsString(value);
    }

    public static void write(Object value, Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, toJson(value));
    }
}
