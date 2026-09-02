package com.example.aifactory.config;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TemporalSdkVersionTest {
    @Test
    void temporalJavaSdkIsExplicitlyVersioned() throws Exception {
        Path pom = List.of(Path.of("pom.xml"), Path.of("apps/orchestrator/pom.xml")).stream()
                .filter(Files::isRegularFile).findFirst().orElseThrow();
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());

        assertThat(document.getElementsByTagName("temporal.version").item(0).getTextContent()).isEqualTo("1.38.0");
        String xml = Files.readString(pom);
        assertThat(xml).contains("<artifactId>temporal-sdk</artifactId>", "<version>${temporal.version}</version>");
    }
}
