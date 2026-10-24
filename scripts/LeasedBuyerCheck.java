import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lazorenkoo.eventrush.crowdgate.CrowdGateClient;
import com.lazorenkoo.eventrush.load.*;
import com.lazorenkoo.eventrush.purchase.ParticipantActivity;
import com.lazorenkoo.eventrush.purchase.ParticipantService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

// Explicit integration check, launched inside the EventRush container with its existing environment.
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({LoadCsvParser.class, LoadRunImportService.class, LoadRunLifecycleService.class,
        LoadClientLeases.class, LoadClientResults.class, LoadParticipantRegistration.class,
        ParticipantService.class, ParticipantActivity.class, CrowdGateClient.class,
        LoadBuyerHttp.class, LoadBuyerAdmission.class, LoadBuyerPurchase.class, LoadBuyerWorker.class})
public class LeasedBuyerCheck {
    public static void main(String[] args) throws Exception {
        var app = new SpringApplication(LeasedBuyerCheck.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        try (var context = app.run("--eventrush.load.enabled=false", "--spring.main.banner-mode=off")) {
            var mapper = context.getBean(ObjectMapper.class);
            var jdbc = context.getBean(JdbcTemplate.class);
            UUID gate;
            try (var http = HttpClient.newHttpClient()) {
                URI origin = URI.create(System.getenv("EVENTRUSH_CROWDGATE_BASE_URL"));
                String key = System.getenv("EVENTRUSH_CROWDGATE_ADMIN_KEY");
                var created = post(http, mapper, origin.resolve("/api/v1/admin/gates"), key, Map.of(
                        "name", "Leased buyer verification", "admissionRate", 1, "maxActiveUsers", 2,
                        "sessionTtlSeconds", 300, "tokenTtlSeconds", 60));
                gate = UUID.fromString(created.path("id").asText());
                post(http, mapper, origin.resolve("/api/v1/admin/gates/" + gate + "/open"), key, null);
            }
            UUID concert = jdbc.queryForObject("""
                    insert into concerts(name,starts_at,sale_starts_at,crowdgate_gate_id,status)
                    values('Leased buyer verification',now()+interval '1 day',now(),?,'ON_SALE') returning id
                    """, UUID.class, gate);
            jdbc.update("""
                    insert into seats(concert_id,section,seat_row,seat_number,price)
                    values(?,'CHECK','1',1,25)
                    """, concert);
            var csv = new ByteArrayInputStream("firstName,lastName,seatNumber\nLeased,Check,1\n"
                    .getBytes(StandardCharsets.UTF_8));
            UUID run = context.getBean(LoadRunImportService.class).importCsv(concert, 1, csv).id();
            context.getBean(LoadRunLifecycleService.class).start(concert, run);
            var leases = context.getBean(LoadClientLeases.class);
            var clients = leases.claim(run, 1);
            if (clients.size() != 1) throw new AssertionError("Expected exactly one leased client");
            var client = clients.getFirst();
            context.getBean(LoadBuyerWorker.class).execute(client);
            String status = jdbc.queryForObject("select status from load_test_clients where id=?",
                    String.class, client.id());
            if (!"SUCCEEDED".equals(status)) throw new AssertionError("Worker result: " + status);
            Boolean named = jdbc.queryForObject("""
                    select exists(select 1 from purchase_participants where user_id=? and run_id=?
                        and source='TEST' and first_name='Leased' and last_name='Check')
                    """, Boolean.class, client.id(), run);
            if (!Boolean.TRUE.equals(named)) throw new AssertionError("Test participant affinity missing");
            leases.reap(run);
            if (!"COMPLETED".equals(jdbc.queryForObject("select status from load_test_runs where id=?",
                    String.class, run))) throw new AssertionError("Run did not complete");
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            boolean delivered = false;
            while (System.nanoTime() < deadline) {
                delivered = Boolean.TRUE.equals(jdbc.queryForObject("""
                        select (select count(*) from ticket_records where user_id=?)=1
                            and (select count(*) from sales_entries s join purchase_participants p
                                on p.reservation_id=s.reservation_id where p.user_id=?)=1
                            and exists(select 1 from simulated_notifications where user_id=?
                                and event_type='ticket.purchased')
                        """, Boolean.class, client.id(), client.id(), client.id()));
                if (delivered) break;
                Thread.sleep(1000);
            }
            if (!delivered) throw new AssertionError("Downstream delivery timed out");
            System.out.println("PASS: leased TEST buyer, genuine queue/session, confirmed purchase, all downstream; run=" + run);
        }
    }

    private static JsonNode post(HttpClient http, ObjectMapper mapper, URI uri, String key, Object body)
            throws Exception {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("X-Admin-Key", key)
                .header("Origin", "http://localhost:8082").header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new AssertionError("Gate setup HTTP " + response.statusCode());
        }
        return response.body().length == 0 ? mapper.nullNode() : mapper.readTree(response.body());
    }
}
