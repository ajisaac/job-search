package co.aisaac.scrapers.util;

import co.aisaac.scrapers.Database;
import co.aisaac.webapp.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.LocalDate;

public class LoadJobs {

    Database db = new Database();

    public static void main(String[] args) {
        LoadJobs loadJobs = new LoadJobs();
        loadJobs.run();
    }

    public void run() {
        ObjectMapper om = new ObjectMapper();

        try {
            File file = new File("/Users/aaron/Documents/jobs.json");
            JsonNode arrayNode = om.readTree(file);

            for (JsonNode node : arrayNode) {
                Job job = new Job();
                job.description = node.get("description").asText();
                job.company = node.get("company").asText();
                job.title = node.get("title").asText();
                job.url = node.get("url").asText();
                job.status = node.get("status").asText();
                job.job_posting_date = LocalDate.parse(node.get("job_posting_date").asText());
                job.job_site = node.get("job_site").asText();
                db.storeJob(job);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
