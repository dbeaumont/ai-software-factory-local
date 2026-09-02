package com.example.aifactory.controller;

import com.example.aifactory.model.LlmMode;
import com.example.aifactory.model.TaskRequest;
import com.example.aifactory.model.TaskState;
import com.example.aifactory.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class PipelineRestContractTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void preservesEveryRouteAndFieldPublishedToPipelineConsumers() throws Exception {
        JsonNode contract = mapper.readTree(Files.readString(contractPath()));
        assertThat(contract.path("compatibility").asText()).isEqualTo("ADDITIVE_ONLY");

        for (JsonNode route : contract.path("routes")) {
            Class<?> controller = "TaskController".equals(route.path("controller").asText())
                    ? TaskController.class : FactoryController.class;
            Method handler = Arrays.stream(controller.getDeclaredMethods())
                    .filter(method -> method.getName().equals(route.path("handler").asText()))
                    .findFirst().orElseThrow();
            RequestMapping root = controller.getAnnotation(RequestMapping.class);
            String relative = "GET".equals(route.path("method").asText())
                    ? firstPath(handler.getAnnotation(GetMapping.class).value())
                    : firstPath(handler.getAnnotation(PostMapping.class).value());
            assertThat(root.value()[0] + relative).isEqualTo(route.path("path").asText());
            ResponseStatus responseStatus = handler.getAnnotation(ResponseStatus.class);
            int actualStatus = responseStatus == null ? HttpStatus.OK.value() : responseStatus.value().value();
            assertThat(actualStatus).isEqualTo(route.path("responseStatus").asInt());
        }

        TaskRequest request = new TaskRequest("https://example.test/repo.git", "main", "change", LlmMode.CLOUD);
        assertThat(mapper.valueToTree(request).propertyNames()).containsAll(textSet(contract.path("taskRequestFields")));
        assertThat(mapper.valueToTree(new TaskState("task-1", "AF-0001", request).view()).propertyNames())
                .containsAll(textSet(contract.path("taskViewFields")));
        assertThat(Arrays.stream(TaskStatus.values()).map(Enum::name).collect(Collectors.toSet()))
                .containsAll(textSet(contract.path("taskStatusValues")));
    }

    private static String firstPath(String[] paths) {
        return paths.length == 0 ? "" : paths[0];
    }

    private static Set<String> textSet(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asText).collect(Collectors.toSet());
    }

    private static Path contractPath() {
        return Path.of(System.getProperty("user.dir"))
                .resolve("../../resources/multiagents/contracts/rest-api-pipeline-v1.1.json").normalize();
    }
}
