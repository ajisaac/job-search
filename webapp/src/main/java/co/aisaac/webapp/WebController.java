package co.aisaac.webapp;

import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class WebController {

    private final Jobs jobRepo;

    public WebController(Jobs jobRepo) {
        this.jobRepo = jobRepo;
    }

    // GET /?status=new&site=all&q=java, kotlin, title: senior, company: acme
    // The list of jobs. The selected job's details are loaded from /job/... by the page.
    public void all(Context ctx) {
        MainFilter filter = filter(ctx);

        List<Job> all = jobRepo.findAll();
        List<Job> jobs = JobFilter.apply(all, filter);

        Map<String, Object> model = new HashMap<>();
        model.put("jobs", jobs);
        model.put("filter", filter);
        model.put("statuses", Job.STATUSES);
        model.put("sites", Job.SITES);
        model.put("statusCounts", JobFilter.countByStatus(all, filter));
        model.put("siteCounts", JobFilter.countBySite(all, filter));
        // the current filters, minus the one a link changes
        model.put("withoutStatus", "site=" + encode(filter.getJobSite()) + "&q=" + encode(filter.getQuery()));
        model.put("withoutSite", "status=" + encode(filter.getStatus()) + "&q=" + encode(filter.getQuery()));

        ctx.render("templates/jobs.html", model);
    }

    // GET /job/{site}/{name}?q=java
    // One job's details, as an html fragment for the right hand pane. Search terms are highlighted.
    public void detail(Context ctx) {
        String id = ctx.pathParam("site") + "/" + ctx.pathParam("name");
        Optional<Job> optJob = jobRepo.findById(id);
        if (optJob.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            return;
        }
        Job job = optJob.get();

        // the statuses of this company's other jobs, so you can see you already applied there
        List<String> others = job.company.isBlank() ? List.of() : jobRepo.findAll().stream()
                .filter(other -> other.company.equalsIgnoreCase(job.company) && !other.id.equals(job.id))
                .map(other -> other.status)
                .toList();

        Map<String, Object> model = new HashMap<>();
        model.put("job", job);
        model.put("description", JobFilter.highlight(job.description, filter(ctx)));
        model.put("others", others);
        model.put("siteLabel", Job.SITES.getOrDefault(job.job_site, job.job_site));
        model.put("statuses", Job.STATUSES);
        model.put("keys", Job.STATUS_KEYS);

        ctx.render("templates/job.html", model);
    }

    // POST /status/{site}/{name}/{status}
    public void updateStatus(Context ctx) {
        String status = ctx.pathParam("status");
        if (!Job.STATUSES.contains(status)) {
            ctx.status(HttpStatus.BAD_REQUEST).result("Unknown status: " + status);
            return;
        }

        String id = ctx.pathParam("site") + "/" + ctx.pathParam("name");
        Optional<Job> optJob = jobRepo.findById(id);
        if (optJob.isEmpty()) {
            ctx.status(HttpStatus.NOT_FOUND);
            return;
        }

        Job job = optJob.get();
        job.status = status;
        jobRepo.save(job);
        ctx.status(HttpStatus.OK);
    }

    private static MainFilter filter(Context ctx) {
        return MainFilter.of(param(ctx, "status", "new"), param(ctx, "site", "all"), param(ctx, "q", ""));
    }

    private static String param(Context ctx, String name, String defaultValue) {
        String value = ctx.queryParam(name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
