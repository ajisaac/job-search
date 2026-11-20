package co.aisaac.scrapers.public_apis.smartrecruiters;

import co.aisaac.scrapers.CompanyBoards;
import co.aisaac.scrapers.CompanyBoards.Company;
import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.nodes.Entities;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes SmartRecruiters job boards through their postings API, for each company in companies/smartrecruiters.txt.
 * The company is its name in jobs.smartrecruiters.com/{company}, and is case sensitive ("ServiceNow").
 * <p>
 * The list of postings has no descriptions, so each new technical job is fetched on its own, with a pause
 * in between. Big companies post thousands of jobs (Bosch has over 5,000), so only the first
 * {@link #MAX_POSTINGS} the API lists are looked at; it appears to list the newest first. The saved url is jobs.smartrecruiters.com/{company}/{id},
 * which is known before fetching, so jobs already saved aren't fetched again.
 */
public class ScrapeSmartRecruiters {

	private static final String JOB_SITE = "smartrecruiters";
	private static final int PAGE_SIZE = 100;
	private static final int MAX_POSTINGS = 1000;

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeSmartRecruiters().run();
	}

	public void run() {
		List<Company> companies = CompanyBoards.read(JOB_SITE);
		int found = 0;
		int saved = 0;

		for (int i = 0; i < companies.size(); i++) {
			if (i > 0) {
				Utils.sleepRandom(3);
			}
			Company company = companies.get(i);
			String api = "https://api.smartrecruiters.com/v1/companies/" + company.slug() + "/postings";

			int total = 0;
			int technical = 0;
			int savedHere = 0;

			for (int offset = 0; offset < MAX_POSTINGS; offset += PAGE_SIZE) {
				Optional<JsonNode> page = readJson(api + "?limit=" + PAGE_SIZE + "&offset=" + offset);
				if (page.isEmpty()) {
					break;
				}
				JsonNode postings = page.get().path("content");

				for (JsonNode posting : postings) {
					total++;
					String title = posting.path("name").asText("").strip();
					if (!CompanyBoards.isTechTitle(title)) {
						continue;
					}
					technical++;

					String url = "https://jobs.smartrecruiters.com/" + company.slug() + "/" + posting.path("id").asText();
					if (store.exists(url)) {
						continue;
					}

					Utils.sleepRandom(2);
					Optional<JsonNode> details = readJson(api + "/" + posting.path("id").asText());
					if (details.isEmpty()) {
						continue;
					}

					Job job = new Job();
					job.url = url;
					job.title = title;
					job.company = company.nameOr(posting.path("company").path("name").asText(""));
					job.description = summary(posting) + description(details.get());
					job.job_site = JOB_SITE;
					job.status = "new";
					job.job_posting_date = LocalDate.now();

					if (store.saveNew(job)) {
						savedHere++;
					}
				}

				if (postings.size() < PAGE_SIZE || offset + PAGE_SIZE >= page.get().path("totalFound").asInt(0)) {
					break;
				}
				Utils.sleepRandom(2);
			}

			CompanyBoards.report(company.slug(), total, technical, savedHere);
			found += technical;
			saved += savedHere;
		}

		System.out.println(JOB_SITE + ": " + companies.size() + " companies, " + found + " found, " + saved + " new");
	}

	private static String summary(JsonNode posting) {
		JsonNode location = posting.path("location");
		String workplace = location.path("remote").asBoolean(false) ? "Remote"
				: location.path("hybrid").asBoolean(false) ? "Hybrid" : "";

		return CompanyBoards.line("Location", location.path("fullLocation").asText("").replaceAll(",\\s*,", ","))
				+ CompanyBoards.line("Workplace", workplace)
				+ CompanyBoards.line("Type", posting.path("typeOfEmployment").path("label").asText(""))
				+ CompanyBoards.line("Level", posting.path("experienceLevel").path("label").asText(""))
				+ CompanyBoards.line("Team", posting.path("department").path("label").asText(""));
	}

	// jobAd.sections holds companyDescription, jobDescription, qualifications and additionalInformation,
	// each with a title and html text.
	private static String description(JsonNode details) {
		StringBuilder html = new StringBuilder();
		JsonNode sections = details.path("jobAd").path("sections");
		for (String name : List.of("jobDescription", "qualifications", "additionalInformation", "companyDescription")) {
			JsonNode section = sections.path(name);
			String text = section.path("text").asText("");
			if (text.isBlank()) {
				continue;
			}
			String title = section.path("title").asText("");
			if (!title.isBlank()) {
				html.append("<h3>").append(Entities.escape(title)).append("</h3>\n");
			}
			html.append(text).append("\n");
		}
		return html.toString();
	}

	private Optional<JsonNode> readJson(String url) {
		return Http.get(url).flatMap(body -> {
			try {
				return Optional.of(mapper.readTree(body));
			} catch (Exception e) {
				System.out.println("Couldn't read " + url + ": " + e.getMessage());
				return Optional.empty();
			}
		});
	}
}
