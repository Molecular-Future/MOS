package org.mos.mcore.tools.time;

import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.joda.time.Seconds;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import java.io.FileInputStream;

@Slf4j
public class JodaTimeHelper {
	static DateTimeFormatter fmt = DateTimeFormat.forPattern("YYYYMMdd'Z'HHmmss");
	static {
		log.info("Get System Available Clock Source:" + checkAvailableClockSource());
		log.info("Get System Current Clock Source:" + checkCurrentClockSource());
	}

	/**
	 * ref to http://pzemtsov.github.io/2017/07/23/the-slow-currenttimemillis.html
	 * 
	 * @return
	 */
	public static long currentMills() {
		return System.currentTimeMillis();
	}

	public static String checkCurrentClockSource() {
		try (FileInputStream fin = new FileInputStream(
				"/sys/devices/system/clocksource/clocksource0/current_clocksource");) {
			byte bb[] = new byte[10240];
			int size = fin.read(bb);
			if (size > 0) {
				return new String(bb, 0, size);
			} else {
				return "UNKNOW(read error)";
			}

		} catch (Throwable e) {
			return "UNKNOW";
		}
	}

	public static String checkAvailableClockSource() {
		try (FileInputStream fin = new FileInputStream(
				"/sys/devices/system/clocksource/clocksource0/available_clocksource");) {
			byte bb[] = new byte[10240];
			int size = fin.read(bb);
			if (size > 0) {
				return new String(bb, 0, size);
			} else {
				return "UNKNOW(read error)";
			}

		} catch (Throwable e) {
			return "UNKNOW";
		}
	}

	public static String current() {
		return fmt.print(System.currentTimeMillis());
	}

	public static String format(long time) {
		return fmt.print(time);
	}

	public static String secondFromNow(long time) {
		return "" + Seconds.secondsBetween(new DateTime(time), new DateTime()).getSeconds();
	}

	public static int secondIntFromNow(long time) {
		return Seconds.secondsBetween(new DateTime(time), new DateTime()).getSeconds();
	}

	public static void main(String[] args) {
		long start = System.currentTimeMillis();
		System.out.println(current());
		System.out.println(checkCurrentClockSource());
		try {
			// Thread.sleep(1*1000);
			System.out.println("dis=" + secondFromNow(start));
			System.out.println("cur==" + System.currentTimeMillis());
			System.out.println("cur==" + currentMills());
			String pattern = "'UTC--'YYYY-MM-dd'T'HH-mm-ss.sss'--'";
			DateTimeFormatter dtf = DateTimeFormat.forPattern(pattern).withZoneUTC();
			String strdate = dtf.print(System.currentTimeMillis());
			System.out.println(strdate);
		} catch (Exception e) {
			e.printStackTrace();
		}

	}
}
