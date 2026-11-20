package co.aisaac.scrapers;

import java.util.Random;
import java.util.concurrent.TimeUnit;

public class Utils {
	public static void sleepRandom(int maxSeconds) {
		long seconds = new Random().nextInt(maxSeconds) + 1;
		try {
			System.out.println("Sleeping for " + seconds + " seconds");
			TimeUnit.SECONDS.sleep(seconds);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

}
