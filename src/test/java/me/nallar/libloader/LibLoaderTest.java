package me.nallar.libloader;

import org.junit.Test;

public class LibLoaderTest {
	@Test
	public void testLibLoader() {
		System.setProperty("LibLoader.modsFolder", "src/test/resources/mods");
		System.setProperty("LibLoader.librariesFolder", "src/test/resources/libraries");
		LibLoader.init();
	}
}
