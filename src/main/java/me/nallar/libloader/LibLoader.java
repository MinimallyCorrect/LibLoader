package me.nallar.libloader;

import lombok.*;
import sun.misc.URLClassPath;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

@SuppressWarnings("WeakerAccess")
public class LibLoader {
	static {
		val mods = System.getProperty("LibLoader.modsFolder", "mods/");
		val libraries = System.getProperty("LibLoader.librariesFolder", "libraries/");
		loadLibraries(new File(mods), new File(libraries));
	}

	/**
	 * Must be called before using any libraries loaded by lib loader
	 * <p>
	 * Recommended to call in a static { } block at the top of a CoreMod
	 */
	public static void init() {
	}

	@SneakyThrows
	private static void loadLibraries(File loadFrom, File extractionDir) {
		val files = loadFrom.listFiles();
		if (files == null)
			throw new FileNotFoundException(loadFrom.getAbsolutePath());

		List<File> searchFiles = new ArrayList<>(Arrays.asList(files));

		val newLibs = new ConcurrentHashMap<String, Library>();
		val allLibs = new ConcurrentHashMap<String, Library>();
		while (true) {
			newLibs.clear();
			boolean[] holder = {false};
			searchFiles.parallelStream().forEach((it) -> {
				if (!it.getName().toLowerCase().endsWith(".jar")) {
					return;
				}
				holder[0] |= loadLibraries(it, allLibs, newLibs);
			});

			if (!holder[0])
				break;

			searchFiles.clear();
			newLibs.values().parallelStream().forEach(lib -> searchFiles.add(lib.save(extractionDir)));
		}

		Map<String, URL> hashToUrl = new ConcurrentHashMap<>();
		allLibs.values().parallelStream().forEach(lib -> hashToUrl.put(lib.sha512hash, lib.getUrl(extractionDir)));

		val classLoader = (URLClassLoader) LibLoader.class.getClassLoader();
		val ucpField = URLClassLoader.class.getDeclaredField("ucp");
		ucpField.setAccessible(true);
		val urls = new ArrayList<URL>(hashToUrl.values());
		urls.addAll(Arrays.asList(classLoader.getURLs()));
		ucpField.set(classLoader, new URLClassPath(urls.toArray(new URL[0])));
	}

	@SneakyThrows
	private static boolean loadLibraries(File source, ConcurrentHashMap<String, Library> libraries, ConcurrentHashMap<String, Library> libraries2) {
		boolean changed = false;
		try (val zis = new ZipInputStream(new FileInputStream(source))) {
			ZipEntry e;
			while ((e = zis.getNextEntry()) != null) {
				if (!e.getName().equals("META-INF/MANIFEST.MF"))
					continue;
				val manifest = new Manifest(zis);
				int i = 0;
				String group;
				while ((group = (String) manifest.getMainAttributes().get("LibLoader-group" + i)) != null) {
					val name = (String) manifest.getMainAttributes().get("LibLoader-name" + i);
					val classifier = (String) manifest.getMainAttributes().get("LibLoader-classifier" + i);
					val version = (String) manifest.getMainAttributes().get("LibLoader-version" + i);
					val sha512hash = (String) manifest.getMainAttributes().get("LibLoader-sha512hash" + i);

					// indicates requirement but not provided here. Should be provided by one of the libs we depend on
					if (sha512hash == null)
						continue;

					val url = (String) manifest.getMainAttributes().get("LibLoader-url" + i);
					val file = (String) manifest.getMainAttributes().get("LibLoader-file" + i);
					val buildTime = (String) manifest.getMainAttributes().get("LibLoader-buildTime" + i);
					val lib = new Library(group, name, classifier, Version.of(version), sha512hash, url, file, buildTime, source);
					//noinspection SynchronizationOnLocalVariableOrMethodParameter
					synchronized (libraries) {
						val oldLib = libraries.get(lib.getKey());
						if (oldLib == null || lib.compareTo(oldLib) > 0) {
							libraries.put(lib.getKey(), lib);
							libraries2.put(lib.getKey(), lib);
							changed = true;
						}
					}
					i++;
				}
			}
		}
		return changed;
	}

	@AllArgsConstructor
	@EqualsAndHashCode
	@ToString
	static class Library implements Comparable<Library> {
		final String group;
		final String name;
		final String classifier;
		final Version version;
		final String sha512hash;
		final String url;
		final String file;
		final String buildTime;
		transient final File source;

		@SneakyThrows
		private static String sha512(File f) {
			val digest = MessageDigest.getInstance("SHA-512");
			byte[] hash = digest.digest(Files.readAllBytes(f.toPath()));

			val hexString = new StringBuilder();
			//noinspection ForLoopReplaceableByForEach
			for (int i = 0; i < hash.length; i++) {
				String hex = Integer.toHexString(0xff & hash[i]);
				if (hex.length() == 1) hexString.append('0');
				hexString.append(hex);
			}

			return hexString.toString();
		}

		String getPath() {
			return group.replace('.', '/') + '/' + name + '-' + version + '-' + sha512hash + '/'
				+ name + '-' + version + '-' + (classifier == null ? "" : '-' + classifier);
		}

		String getKey() {
			return group + '.' + name;
		}

		@SneakyThrows
		private void validateHash(File jarPath) {
			if (!jarPath.exists())
				throw new FileNotFoundException("Couldn't extract/download library " + this);
			val hash = sha512(jarPath);
			if (!hash.equals(sha512hash))
				throw new Error("Wrong hash for library " + this + "\nExpected " + sha512hash + ", got " + hash);
		}

		@SneakyThrows
		File save(File extractionDir) {
			val jarPath = new File(extractionDir, getPath() + ".jar");
			if (!jarPath.exists() || !sha512(jarPath).equals(sha512hash)) {
				//noinspection ResultOfMethodCallIgnored
				jarPath.getParentFile().mkdirs();
				if (file != null) {
					try (val zis = new ZipInputStream(new FileInputStream(source))) {
						ZipEntry e;
						while ((e = zis.getNextEntry()) != null) {
							if (!e.getName().equals(file))
								continue;
							Files.copy(zis, jarPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
						}
					}
				} else if (url != null) {
					Files.copy(new URL(url).openStream(), jarPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
				} else {
					throw new Error("No way to acquire dependency: " + this);
				}
			}

			validateHash(jarPath);

			return jarPath;
		}

		@SneakyThrows
		URL getUrl(File extractionDir) {
			return new File(extractionDir, getPath() + ".jar").toURI().toURL();
		}

		@Override
		public int compareTo(Library o) {
			val c = version.compareTo(o.version);
			if (c != 0)
				return c;
			return Long.compare(Long.parseLong(buildTime), Long.parseLong(o.buildTime));
		}
	}

	public static class Version implements Comparable<Version> {
		private final int[] parts;
		private final String suffix;

		private Version(String version) {
			if (version == null)
				throw new IllegalArgumentException("Version can not be null");
			version = version.trim();

			int dash = version.indexOf('-');
			if (dash != -1) {
				suffix = version.substring(dash + 1).trim();
				version = version.substring(0, dash);
			} else {
				suffix = null;
			}

			if (!version.matches("[0-9]+(\\.[0-9]+)*"))
				throw new IllegalArgumentException("Invalid version format. Should consist of digits and dots with optional suffix after -. Got '" + version + "'");
			parts = Arrays.stream(version.split("\\.")).mapToInt(Integer::parseInt).toArray();
		}

		public static Version of(String s) {
			return new Version(s);
		}

		@Override
		public int compareTo(@SuppressWarnings("NullableProblems") Version that) {
			if (that == null)
				return 1;

			if (this == that)
				return 0;

			int length = Math.max(parts.length, that.parts.length);
			for (int i = 0; i < length; i++) {
				int thisPart = i < parts.length ? parts[i] : 0;
				int thatPart = i < that.parts.length ? that.parts[i] : 0;

				if (thisPart < thatPart)
					return -1;
				if (thisPart > thatPart)
					return 1;
			}

			val a = suffix;
			val b = that.suffix;
			if (Objects.equals(a, b))
				return 0;
			int s = Integer.compare(suffixInt(), that.suffixInt());
			if (s != 0)
				return s;
			if (a == null)
				return -1;
			return a.compareTo(b);
		}

		private int suffixInt() {
			if (suffix == null)
				return 0;

			switch (suffix.toLowerCase().trim()) {
				case "alpha":
					return -3;
				case "beta":
				case "snapshot":
					return -2;
				case "":
					return 0;
				default:
					return -1;
			}
		}

		@Override
		public String toString() {
			return String.join(".", (Iterable<String>) (Arrays.stream(parts).mapToObj(String::valueOf)::iterator)) + (suffix == null ? "" : '-' + suffix);
		}

		@Override
		public int hashCode() {
			return toString().hashCode();
		}

		@Override
		public boolean equals(Object that) {
			return this == that || that != null && this.getClass() == that.getClass() && this.compareTo((Version) that) == 0;
		}
	}
}
