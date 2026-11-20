package com.threadfit.glucopilot.device.cloud;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuaweiHealthCloudClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void normalizeKeepsSamplePointsShape() throws Exception {
        DeviceCloudProperties props = new DeviceCloudProperties();
        HuaweiHealthCloudClient client = new HuaweiHealthCloudClient(props, RestClient.create(), mapper);

        ObjectNode root = mapper.createObjectNode();
        root.putArray("samplePoints").addObject().put("startTime", 1L);
        var normalized = client.normalize(root);
        assertTrue(normalized.has("samplePoints"));
        assertEquals(1, normalized.get("samplePoints").size());
    }

    @Test
    void normalizeExtractsNestedGroupSamplePoints() throws Exception {
        DeviceCloudProperties props = new DeviceCloudProperties();
        HuaweiHealthCloudClient client = new HuaweiHealthCloudClient(props, RestClient.create(), mapper);

        String raw = """
                {
                  "group": [{
                    "sampleSet": [{
                      "samplePoints": [{
                        "startTime": 1760400900000,
                        "value": [{"fieldName":"level","floatValue":6.7}]
                      }]
                    }]
                  }]
                }
                """;
        var normalized = client.normalize(mapper.readTree(raw));
        assertEquals(1, normalized.get("samplePoints").size());
        assertEquals(6.7, normalized.get("samplePoints").get(0).get("value").get(0).get("floatValue").asDouble());
    }
}
