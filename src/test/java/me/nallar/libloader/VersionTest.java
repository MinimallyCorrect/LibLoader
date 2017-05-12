package me.nallar.libloader;

import org.junit.Assert;
import org.junit.Test;

public class VersionTest {
	@Test
	public void testVersion() {
		Assert.assertTrue(LibLoader.Version.of("1").compareTo(LibLoader.Version.of("0.1")) > 0);
		Assert.assertTrue(LibLoader.Version.of("1").compareTo(LibLoader.Version.of("1-SNAPSHOT")) > 0);
	}
}
