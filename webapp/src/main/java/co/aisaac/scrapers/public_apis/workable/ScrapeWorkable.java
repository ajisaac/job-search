package co.aisaac.scrapers.public_apis.workable;

import co.aisaac.scrapers.CompanyBoards;
import co.aisaac.scrapers.CompanyBoards.Company;
import co.aisaac.scrapers.Http;
import co.aisaac.scrapers.Utils;
import co.aisaac.webapp.Job;
import co.aisaac.webapp.Jobs;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scrapes Workable job boards through their widget API, one GET per company from companies/workable.txt,
 * full descriptions included. The company is its name in apply.workable.com/{company}.
 */
public class ScrapeWorkable {

	private static final String JOB_SITE = "workable";

	private final Jobs store = new Jobs();
	private final ObjectMapper mapper = new ObjectMapper();

	public static void main(String[] args) {
		new ScrapeWorkable().run();
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

			Optional<JsonNode> response = readJson(
					"https://apply.workable.com/api/v1/widget/accounts/" + company.slug() + "?details=true");
			if (response.isEmpty()) {
				continue;
			}

			String companyName = company.nameOr(response.get().path("name").asText(""));
			JsonNode postings = response.get().path("jobs");
			int technical = 0;
			int savedHere = 0;

			for (JsonNode posting : postings) {
				String title = posting.path("title").asText("").strip();
				if (!CompanyBoards.isTechTitle(title)) {
					continue;
				}
				technical++;

				Job job = new Job();
				job.url = posting.path("url").asText("").strip();
				job.title = title;
				job.company = companyName;
				job.description = summary(posting) + posting.path("description").asText("");
				job.job_site = JOB_SITE;
				job.status = "new";
				job.job_posting_date = LocalDate.now();

				if (!job.url.isEmpty() && store.saveNew(job)) {
					savedHere++;
				}
			}

			CompanyBoards.report(company.slug(), postings.size(), technical, savedHere);
			found += technical;
			saved += savedHere;
		}

		System.out.println(JOB_SITE + ": " + companies.size() + " companies, " + found + " found, " + saved + " new");
	}

	private static String summary(JsonNode posting) {
		List<String> locations = new ArrayList<>();
		for (JsonNode location : posting.path("locations")) {
			if (location.path("hidden").asBoolean(false)) {
				continue;
			}
			String city = location.path("city").asText("");
			String country = location.path("country").asText("");
			locations.add(city.isEmpty() ? country : country.isEmpty() ? city : city + ", " + country);
		}

		return CompanyBoards.line("Location", String.join("; ", locations))
				+ CompanyBoards.line("Workplace", posting.path("telecommuting").asBoolean(false) ? "Remote" : "")
				+ CompanyBoards.line("Type", posting.path("employment_type").asText(""))
				+ CompanyBoards.line("Team", posting.path("department").asText(""));
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
