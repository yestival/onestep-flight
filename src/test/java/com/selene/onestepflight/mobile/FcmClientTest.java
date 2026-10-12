package com.selene.onestepflight.mobile;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

class FcmClientTest {
    @TempDir Path directory;
    private PushDelivery message(String payload) {
        var delivery=new PushDelivery();delivery.payload=payload;delivery.token="test-token";return delivery;
    }
    @Test void missingConfigurationLeavesPushUnavailable() {
        var client=new FcmClient("","",new ObjectMapper());
        assertFalse(client.ready());assertEquals("FCM_NOT_READY",client.send(message("{}")).reason());
    }
    @Test void corruptCredentialFileIsConfigurationFailureRatherThanNetworkOrInvalidToken() throws Exception {
        var file=Files.writeString(directory.resolve("invalid-credential.json"),"invalid json");
        var client=new FcmClient(file.toString(),"test-project",new ObjectMapper());
        var result=client.send(message("{}"));
        assertEquals(403,result.status());assertEquals("FCM_CONFIGURATION_ERROR",result.reason());assertFalse(client.ready());
    }
    @Test void oversizedMessageIsRejectedBeforeFirebaseInitialization() throws Exception {
        var file=Files.writeString(directory.resolve("unused-credential.txt"),"not used");
        var client=new FcmClient(file.toString(),"test-project",new ObjectMapper());
        assertEquals(413,client.send(message(new ObjectMapper().writeValueAsString(java.util.Map.of("body","x".repeat(3801))))).status());
        assertTrue(client.ready());
    }
    @Test void dataSizeCountsUtf8KeysAndUnescapedValues() {
        assertEquals(12,FcmClient.dataBytes(java.util.Map.of("body","中文\"x")));
    }
}
