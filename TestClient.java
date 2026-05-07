import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thinkingcoding.rag.codegraph.GitNexusCodeGraphMapper;
import java.nio.file.Path;
public class TestClient {
    public static void main(String[] args) throws Exception {
        String json = "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"status\\\":\\\"found\\\",\\\"symbol\\\":{\\\"uid\\\":\\\"Class:src/main/java/com/thinkingcoding/ThinkingCodingCLI.java:ThinkingCodingCLI\\\",\\\"name\\\":\\\"ThinkingCodingCLI\\\"},\\\"incoming\\\":{},\\\"outgoing\\\":{}}\"}]}";
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> map = mapper.readValue(json, Map.class);
        GitNexusCodeGraphMapper mapperObj = new GitNexusCodeGraphMapper();
        GitNexusCodeGraphMapper.GitNexusMappingResult result = mapperObj.map(Path.of("D:/ThinkingCoding"), "ThinkingCodingCLI", map);
        System.out.println(result.getTarget());
    }
}
