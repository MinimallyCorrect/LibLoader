package me.nallar.libloader;

import org.junit.Assert;
import org.junit.Test;
import me.nallar.libloader.LibLoader.Version;

public class VersionTest {
	@Test
	public void testVersion() {
		Assert.assertTrue(Version.of("1").compareTo(Version.of("0.1")) > 0);
		Assert.assertTrue(Version.of("1").compareTo(Version.of("1-SNAPSHOT")) > 0);
	}
}
