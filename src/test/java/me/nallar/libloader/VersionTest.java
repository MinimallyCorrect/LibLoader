package me.nallar.libloader;

import org.junit.Assert;
import org.junit.Test;

public class VersionTest {
	@Test
	public void testVersion() {
		Assert.assertTrue(new LibLoaderChained.Version("1").compareTo(new LibLoaderChained.Version("0.1")) > 0);
		Assert.assertTrue(new LibLoaderChained.Version("1").compareTo(new LibLoaderChained.Version("1-SNAPSHOT")) > 0);
	}
}
