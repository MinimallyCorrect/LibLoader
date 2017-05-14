package me.nallar.libloader;

import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.val;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.jar.*;
import java.util.zip.*;

@SuppressWarnings("WeakerAccess")
public class LibLoaderChained {
	static final Logger log = LogManager.getLogger("LibLoader");
	static final boolean DISABLE_VALIDATION = Boolean.parseBoolean(System.getProperty("LibLoader.disableValidation", "false"));
	static final AtomicBoolean inited = new AtomicBoolean();

	/**
	 * Must be called before using any libraries loaded by lib loader
	 * <p>
	 * Recommended to call in a static { } block at the top of a CoreMod
	 */
	@SneakyThrows
	public static void init() {
		if (!inited.compareAndSet(false, true))
			return;

		val mods = new File(System.getProperty("LibLoader.modsFolder", "mods/"));
		val libraries = new File(System.getProperty("LibLoader.librariesFolder", "libraries/"));

		val cachedLibsFile = new File(libraries, "libloader cached libs.txt");
		List<File> libs = null;
		if (!Boolean.parseBoolean(System.getProperty("LibLoader.anyChanges", "true"))) {
			libs = loadCachedLibs(cachedLibsFile);
		}

		if (libs == null) {
			val files = mods.listFiles();
			if (files == null)
				throw new FileNotFoundException(mods.getAbsolutePath());

			List<File> searchFiles = Collections.synchronizedList(new ArrayList<>(Arrays.asList(files)));

			val newLibs = new ConcurrentHashMap<String, Library>();
			val allLibs = new ConcurrentHashMap<String, Library>();
			while (true) {
				newLibs.clear();
				searchFiles.parallelStream().forEach(it -> {
					if (!it.getName().toLowerCase().endsWith(".jar")) {
						return;
					}
					loadLibraries(it, allLibs, newLibs);
				});

				if (newLibs.isEmpty())
					break;

				searchFiles.clear();
				newLibs.values().parallelStream().forEach(lib -> searchFiles.add(lib.save(libraries)));
			}

			log.info("Found libs: " + allLibs);
			Map<String, File> hashToFile = new ConcurrentHashMap<>();
			allLibs.values().parallelStream().forEach(lib -> hashToFile.put(lib.sha512hash, lib.getFile(libraries)));
			libs = new ArrayList<>(hashToFile.values());
			libs.sort(Comparator.comparing(File::getPath));
		}

		if (libs.isEmpty())
			return;

		val classLoader = (LaunchClassLoader) LibLoaderChained.class.getClassLoader();
		log.info("LaunchClassLoader URLs: " + (Arrays.asList(classLoader.getURLs())).toString().replace(", ", ",\n"));
		for (File lib : libs) {
			classLoader.addURL(lib.toURI().toURL());
		}
		saveCachedLibs(cachedLibsFile, libs);
	}

	private static List<File> loadCachedLibs(File cachedLibsFile) {
		val cachedLibs = new ArrayList<File>();
		try {
			for (val path : Files.readAllLines(cachedLibsFile.toPath(), Charset.forName("UTF-8"))) {
				val file = new File(path);
				if (!file.exists())
					return null;
				cachedLibs.add(file);
			}
		} catch (IOException ignored) {
			return null;
		}
		return cachedLibs;
	}

	private static void saveCachedLibs(File cachedLibsFile, List<File> cachedLibs) {
		val sb = new StringBuilder();
		cachedLibs.forEach(it -> sb.append(it.getPath()).append('\n'));
		try {
			Files.write(cachedLibsFile.toPath(), sb.toString().getBytes(Charset.forName("UTF-8")), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
		} catch (IOException e) {
			System.err.println("Failed to store cached libs");
			e.printStackTrace();
		}
	}

	@SneakyThrows
	static void loadLibraries(File source, ConcurrentHashMap<String, Library> libraries, ConcurrentHashMap<String, Library> libraries2) {
		try (val zis = new ZipInputStream(new FileInputStream(source))) {
			ZipEntry e;
			while ((e = zis.getNextEntry()) != null) {
				if (!e.getName().equals("META-INF/MANIFEST.MF"))
					continue;

				val manifest = new Manifest(zis);
				int i = 0;
				val main = manifest.getMainAttributes();
				String group;
				while ((group = main.getValue("LibLoader-group" + i)) != null) {
					val name = main.getValue("LibLoader-name" + i);
					val classifier = main.getValue("LibLoader-classifier" + i);
					val version = main.getValue("LibLoader-version" + i);
					val sha512hash = main.getValue("LibLoader-sha512hash" + i);

					// indicates requirement but not provided here. Should be provided by one of the libs we depend on
					// TODO: can check that
					if (sha512hash == null) {
						i++;
						continue;
					}

					val url = main.getValue("LibLoader-url" + i);
					val file = main.getValue("LibLoader-file" + i);
					val buildTime = main.getValue("LibLoader-buildTime" + i);
					val lib = new Library(group, name, classifier, new Version(version), sha512hash, url, file, buildTime, source);
					//noinspection SynchronizationOnLocalVariableOrMethodParameter
					synchronized (libraries) {
						val oldLib = libraries.get(lib.getKey());
						if (oldLib == null || lib.compareTo(oldLib) > 0) {
							libraries.put(lib.getKey(), lib);
							libraries2.put(lib.getKey(), lib);
						}
					}
					i++;
				}
				return;
			}
		}
	}

	@EqualsAndHashCode
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
		transient String calculatedHash = null;

		Library(String group, String name, String classifier, Version version, String sha512hash, String url, String file, String buildTime, File source) {
			this.group = group;
			this.name = name;
			this.classifier = classifier;
			this.version = version;
			this.sha512hash = sha512hash;
			this.url = url;
			this.file = file;
			this.buildTime = buildTime;
			this.source = source;
		}

		@SneakyThrows
		static String sha512(File f) {
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

		@SneakyThrows
		static InputStream openStream(URL url) {
			val con = url.openConnection();
			con.setConnectTimeout(10000);
			con.setReadTimeout(10000);
			return con.getInputStream();
		}

		String getPath() {
			return group.replace('.', '/') + '/' + name + '-' + version + '-' + sha512hash + '/'
				+ name + '-' + version + '-' + (classifier == null ? "" : '-' + classifier) + ".jar";
		}

		String getKey() {
			return group + '.' + name;
		}

		@SneakyThrows
		void validateHash(File jarPath) {
			if (!jarPath.exists())
				throw new FileNotFoundException("Couldn't extract/download library " + this);
			val hash = sha512(jarPath);
			calculatedHash = hash;

			if (!hash.equals(sha512hash)) {
				val error = new Error("Wrong hash for library " + this + "\nExpected " + sha512hash + ", got " + hash);
				if (DISABLE_VALIDATION) {
					error.printStackTrace();
				} else {
					throw error;
				}
			}
		}

		@SneakyThrows
		File save(File extractionDir) {
			val jarPath = getFile(extractionDir);
			if (!jarPath.exists() || (!DISABLE_VALIDATION && !sha512(jarPath).equals(sha512hash))) {
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
					log.info("Downloading library " + toString() + " from " + url + ". Expected hash: " + sha512hash);
					try (val is = openStream(new URL(url))) {
						Files.copy(is, jarPath.toPath(), StandardCopyOption.REPLACE_EXISTING);
					}
				} else {
					throw new Error("No way to acquire dependency: " + this);
				}
			}

			validateHash(jarPath);

			return jarPath;
		}

		@SneakyThrows
		File getFile(File extractionDir) {
			return new File(extractionDir, getPath());
		}

		@Override
		public int compareTo(Library o) {
			val c = version.compareTo(o.version);
			if (c != 0)
				return c;
			return Long.compare(Long.parseLong(buildTime), Long.parseLong(o.buildTime));
		}

		@Override
		public String toString() {
			return group + '.' + name + (classifier == null ? "" : '-' + classifier) + '-' + version;
		}
	}

	@SuppressWarnings("Duplicates")
	static class Version implements Comparable<Version> {
		final int[] parts;
		final String suffix;

		Version(String version) {
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

		int suffixInt() {
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
