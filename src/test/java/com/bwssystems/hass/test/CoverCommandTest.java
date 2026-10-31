package com.bwssystems.hass.test;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.bwssystems.HABridge.BridgeSettings;
import com.bwssystems.HABridge.IpList;
import com.bwssystems.HABridge.NamedIP;
import com.bwssystems.HABridge.api.CallItem;
import com.bwssystems.HABridge.plugins.hass.HassHome;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Exercise JSON decoding, brightness substitution and the actual HTTP request. */
public class CoverCommandTest {
    private HttpServer server;
    private HassHome home;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/services/", this::capture);
        server.start();
        NamedIP address = new NamedIP();
        address.setName("HA");
        address.setIp("127.0.0.1");
        address.setPort(Integer.toString(server.getAddress().getPort()));
        address.setPassword("test-token");
        IpList addresses = new IpList();
        addresses.setDevices(Collections.singletonList(address));
        BridgeSettings settings = new BridgeSettings();
        settings.getBridgeSettingsDescriptor().setHassaddress(addresses);
        home = new HassHome(settings);
    }

    @After
    public void tearDown() {
        server.stop(0);
        // HTTPHome's client pool is shared with other tests; do not close it here.
    }

    private void capture(HttpExchange exchange) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = exchange.getRequestBody().read(buffer)) != -1) {
            bytes.write(buffer, 0, length);
        }
        requests.add(new Request(exchange.getRequestURI().getPath(),
                exchange.getRequestMethod(), exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"),
                new JsonParser().parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject()));
        byte[] response = "[]".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String send(String entity, String state, String brightness, Integer target, Integer increment,
            boolean stringItem) {
        JsonObject command = new JsonObject();
        command.addProperty("entityId", entity);
        command.addProperty("hassName", "HA");
        if (state != null) command.addProperty("state", state);
        if (brightness != null) command.addProperty("bri", brightness);
        CallItem item = new CallItem();
        item.setItem(stringItem ? new JsonPrimitive(command.toString()) : command);
        return home.deviceHandler(item, null, "7", 100, target, increment, null, null, "{}");
    }

    private Request expect(String domain, String service, String entity) throws InterruptedException {
        Request request = requests.poll(2, TimeUnit.SECONDS);
        assertNotNull("Expected a Home Assistant HTTP request", request);
        assertEquals("/api/services/" + domain + "/" + service, request.path);
        assertEquals("POST", request.method);
        assertEquals("Bearer test-token", request.authorization);
        assertTrue(request.contentType.startsWith("application/json"));
        assertEquals(entity, request.body.get("entity_id").getAsString());
        return request;
    }

    @Test
    public void openCloseAndStopUseCoverServices() throws Exception {
        String[] states = {"on", "off", "stop", "ON", "OFF", "STOP"};
        String[] services = {"open_cover", "close_cover", "stop_cover"};
        for (int i = 0; i < states.length; i++) {
            assertNull(send("cover.armadio", states[i], null, null, null, false));
            assertEquals(1, expect("cover", services[i % 3], "cover.armadio").body.entrySet().size());
        }
    }

    @Test
    public void originalDimPayloadConvertsByteToPosition() throws Exception {
        int[] brightness = {0, 1, 64, 127, 128, 191, 254, 255};
        int[] positions = {0, 0, 25, 50, 50, 75, 100, 100};
        for (int i = 0; i < brightness.length; i++) {
            assertNull(send("cover.esp32_sala_armadio", "on", "${intensity.byte}", brightness[i], null, false));
            Request request = expect("cover", "set_cover_position", "cover.esp32_sala_armadio");
            assertEquals(positions[i], request.body.get("position").getAsInt());
            assertEquals(2, request.body.entrySet().size());
            assertFalse(request.body.has("brightness"));
        }
    }

    @Test
    public void stringItemsAndRelativeBrightnessWork() throws Exception {
        assertNull(send("cover.armadio", "on", "${intensity.byte}", null, 28, true));
        assertEquals(50, expect("cover", "set_cover_position", "cover.armadio").body.get("position").getAsInt());
    }

    @Test
    public void fixedFractionalBrightnessIsRounded() throws Exception {
        assertNull(send("cover.armadio", "on", "127.5", null, null, false));
        assertEquals(50, expect("cover", "set_cover_position", "cover.armadio").body.get("position").getAsInt());
    }

    @Test
    public void closeAndStopIgnoreBrightness() throws Exception {
        assertNull(send("cover.armadio", "off", "255", null, null, false));
        assertEquals(1, expect("cover", "close_cover", "cover.armadio").body.entrySet().size());
        assertNull(send("cover.armadio", "stop", "invalid", null, null, false));
        assertEquals(1, expect("cover", "stop_cover", "cover.armadio").body.entrySet().size());
    }

    @Test
    public void invalidBrightnessReturnsHueErrorWithoutMovingCover() {
        for (String brightness : new String[] {"", "bad", "${unknown}", "NaN", "Infinity", "-1", "256"}) {
            assertHueError(send("cover.armadio", "on", brightness, null, null, false));
        }
        assertTrue(requests.isEmpty());
    }

    @Test
    public void invalidOrMissingStateDoesNotCloseCover() {
        for (String state : new String[] {null, "", "typo", "open"}) {
            assertHueError(send("cover.armadio", state, null, null, null, false));
        }
        assertTrue(requests.isEmpty());
    }

    private void assertHueError(String result) {
        assertNotNull(result);
        JsonObject error = new JsonParser().parse(result).getAsJsonArray().get(0)
                .getAsJsonObject().getAsJsonObject("error");
        assertEquals(6, error.get("type").getAsInt());
        assertEquals("/lights/7/state", error.get("address").getAsString());
    }

    @Test
    public void lightSwitchScriptAndGroupKeepExistingServices() throws Exception {
        assertNull(send("light.sala", "on", "${intensity.byte}", 128, null, false));
        Request light = expect("light", "turn_on", "light.sala");
        assertEquals(128, light.body.get("brightness").getAsInt());
        assertFalse(light.body.has("position"));
        for (String entity : new String[] {"light.sala", "switch.sala", "script.apri", "group.sala"}) {
            String domain = entity.startsWith("group.") ? "homeassistant" : entity.split("\\.")[0];
            for (String state : new String[] {"on", "off"}) {
                assertNull(send(entity, state, null, null, null, false));
                assertEquals(1, expect(domain, "turn_" + state, entity).body.entrySet().size());
            }
        }
    }

    private static class Request {
        final String path, method, authorization, contentType;
        final JsonObject body;
        Request(String path, String method, String authorization, String contentType, JsonObject body) {
            this.path = path;
            this.method = method;
            this.authorization = authorization;
            this.contentType = contentType;
            this.body = body;
        }
    }
}
