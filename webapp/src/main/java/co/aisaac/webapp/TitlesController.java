package co.aisaac.webapp;

import io.javalin.http.Context;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TitlesController {

    private final Jobs jobRepo;

    public TitlesController(Jobs jobRepo) {
        this.jobRepo = jobRepo;
    }

    // GET /titles?title=backend, platform
    // Every new job's title, for quickly declining or marking interested. Comma separated title terms, any match.
    public void all(Context ctx) {
        TitlesFilter filter = new TitlesFilter();
        String title = ctx.queryParam("title");
        filter.titleSearch = title == null ? "" : title;

        List<String> terms = Arrays.stream(filter.titleSearch.split(","))
                .map(term -> term.trim().toLowerCase(Locale.ROOT))
                .filter(term -> !term.isEmpty())
                .toList();

        List<Job> jobs = jobRepo.findAllByStatusOrderByTitle("new");

        if (!terms.isEmpty()) {
            jobs = jobs.stream()
                    .filter(job -> terms.stream().anyMatch(job.title.toLowerCase(Locale.ROOT)::contains))
                    .toList();
        }

        ctx.render("templates/titles.html", Map.of(
                "jobs", jobs,
                "filter", filter,
                "sites", Job.SITES
        ));
    }
}
