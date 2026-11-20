package co.aisaac.scrapers;

import co.aisaac.scrapers.embedded_json.arcdev.ScrapeArcDev;
import co.aisaac.scrapers.embedded_json.justremote.ScrapeJustRemote;
import co.aisaac.scrapers.embedded_json.simplyhired.ScrapeSimplyHired;
import co.aisaac.scrapers.html.builtin.ScrapeBuiltIn;
import co.aisaac.scrapers.html.dice.ScrapeDice;
import co.aisaac.scrapers.html.skipthedrive.ScrapeSkipTheDrive;
import co.aisaac.scrapers.public_apis.ashby.ScrapeAshby;
import co.aisaac.scrapers.public_apis.greenhouse.ScrapeGreenhouse;
import co.aisaac.scrapers.public_apis.hackernews.ScrapeHackerNews;
import co.aisaac.scrapers.public_apis.himalayas.ScrapeHimalayas;
import co.aisaac.scrapers.public_apis.jobspresso.ScrapeJobspresso;
import co.aisaac.scrapers.public_apis.lever.ScrapeLever;
import co.aisaac.scrapers.public_apis.nodesk.ScrapeNoDesk;
import co.aisaac.scrapers.public_apis.recruitee.ScrapeRecruitee;
import co.aisaac.scrapers.public_apis.remoteok.ScrapeRemoteOk;
import co.aisaac.scrapers.public_apis.remotive.ScrapeRemotive;
import co.aisaac.scrapers.public_apis.smartrecruiters.ScrapeSmartRecruiters;
import co.aisaac.scrapers.public_apis.weworkremotely.ScrapeWeWorkRemotely;
import co.aisaac.scrapers.public_apis.workable.ScrapeWorkable;
import co.aisaac.scrapers.public_apis.workingnomads.ScrapeWorkingNomads;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Runs every scraper that works without a browser, one after another, from the folder that holds {@code jobs/} and {@code companies/}.
 * <p>
 * With no arguments it runs them all. Arguments pick some by name, e.g. {@code nodesk remotive greenhouse}.
 * A scraper that throws is reported and the rest still run. Each scraper prints its own
 * "found, new" line; this adds how long each took and which failed.
 * <p>
 * Meant to be run daily by cron or launchd. Remotive asks for at most 4 requests a day.
 */
public class RunScrapers {

	// name -> scraper, in the order they run. Single feeds first, then the company boards, then pages with embedded JSON, then html pages.
	private static final Map<String, Runnable> SCRAPERS = new LinkedHashMap<>();

	static {
		SCRAPERS.put("nodesk", () -> new ScrapeNoDesk().run());
		SCRAPERS.put("remotive", () -> new ScrapeRemotive().run());
		SCRAPERS.put("jobspresso", () -> new ScrapeJobspresso().run());
		SCRAPERS.put("weworkremotely", () -> new ScrapeWeWorkRemotely().run());
		SCRAPERS.put("remoteok", () -> new ScrapeRemoteOk().run());
		SCRAPERS.put("himalayas", () -> new ScrapeHimalayas().run());
		SCRAPERS.put("workingnomads", () -> new ScrapeWorkingNomads().run());
		SCRAPERS.put("hackernews", () -> new ScrapeHackerNews().run());
		SCRAPERS.put("greenhouse", () -> new ScrapeGreenhouse().run());
		SCRAPERS.put("lever", () -> new ScrapeLever().run());
		SCRAPERS.put("ashby", () -> new ScrapeAshby().run());
		SCRAPERS.put("workable", () -> new ScrapeWorkable().run());
		SCRAPERS.put("smartrecruiters", () -> new ScrapeSmartRecruiters().run());
		SCRAPERS.put("recruitee", () -> new ScrapeRecruitee().run());
		SCRAPERS.put("arcdev", () -> new ScrapeArcDev().run());
		SCRAPERS.put("justremote", () -> new ScrapeJustRemote().run());
		SCRAPERS.put("simplyhired", () -> new ScrapeSimplyHired().run());
		SCRAPERS.put("dice", () -> new ScrapeDice().run());
		SCRAPERS.put("builtin", () -> new ScrapeBuiltIn().run());
		SCRAPERS.put("skipthedrive", () -> new ScrapeSkipTheDrive().run());
	}

	public static void main(String[] args) {
		List<String> names = new ArrayList<>();
		for (String arg : args) {
			String name = arg.toLowerCase(Locale.ROOT);
			if (!SCRAPERS.containsKey(name)) {
				System.out.println("Unknown scraper \"" + arg + "\". Known: " + String.join(", ", SCRAPERS.keySet()));
				System.exit(2);
			}
			names.add(name);
		}
		if (names.isEmpty()) {
			names.addAll(SCRAPERS.keySet());
		}

		Instant start = Instant.now();
		List<String> failed = new ArrayList<>();

		for (String name : names) {
			System.out.println("\n===== " + name + " =====");
			Instant scraperStart = Instant.now();
			try {
				SCRAPERS.get(name).run();
			} catch (Exception e) {
				failed.add(name);
				System.out.println(name + " failed: " + e);
				e.printStackTrace(System.out);
			}
			System.out.println(name + " took " + seconds(scraperStart) + "s");
		}

		System.out.println("\n===== done =====");
		System.out.println("Ran " + names.size() + " scrapers in " + seconds(start) + "s");
		if (!failed.isEmpty()) {
			System.out.println("Failed: " + String.join(", ", failed));
			System.exit(1);
		}
	}

	private static long seconds(Instant since) {
		return Duration.between(since, Instant.now()).toSeconds();
	}
}
