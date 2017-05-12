package me.nallar.libloader;

import lombok.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import java.util.zip.*;

public class LibLoader {
	/**
	 * Must be called before using any libraries loaded by lib loader
	 *
	 * Recommended to call in a static { } block at the top of a CoreMod
	 */
	public static void init() { }

	static {
		loadLibraries(new File("mods/"), new File("libraries/"));
	}

	@SneakyThrows
	private static void loadLibraries(File loadFrom, File extractionDir) {
		val files = loadFrom.listFiles();
		if (files == null)
			throw new FileNotFoundException(loadFrom.getAbsolutePath());

		val libraries = new ConcurrentHashMap<String, List<Library>>();
		Arrays.asList(files).parallelStream().forEach((it) -> {
			if (!it.getName().toLowerCase().endsWith(".jar")) {
				return;
			}
			try (val zis = new ZipInputStream(new FileInputStream(it))) {
				ZipEntry e;
				while ((e = zis.getNextEntry()) != null) {
					if (!e.getName().equals("META-INF/MANIFEST.MF"))
						continue;
					val manifest = new Manifest(zis);
					int i = 0;
					String group;
					while ((group = (String) manifest.getMainAttributes().get("LibLoader-group" + i)) != null) {
						val name = (String) manifest.getMainAttributes().get("LibLoader-name" + i);
						val artifact = (String) manifest.getMainAttributes().get("LibLoader-artifact" + i);
						val version = (String) manifest.getMainAttributes().get("LibLoader-version" + i);
						val url = (String) manifest.getMainAttributes().get("LibLoader-url" + i);
						val file = (String) manifest.getMainAttributes().get("LibLoader-file" + i);
						val buildTime = (String) manifest.getMainAttributes().get("LibLoader-buildTime" + i);
						val lib = new Library(group, name, artifact, Version.of(version), url, file, buildTime, it);
						libraries.computeIfAbsent(lib.getKey(), unused -> new ArrayList<>()).add(lib);
						i++;
					}
				}
			} catch (IOException e) {
				throw new IOError(e);
			}
		});

		val urls = Collections.synchronizedList(new ArrayList<>(libraries.size()));
		libraries.values().parallelStream().forEach(lib -> {
			val newest = Collections.max(lib, Comparator.comparing(a -> a.version));
			urls.add(newest.save(extractionDir));
		});
	}

	@AllArgsConstructor
	@EqualsAndHashCode
	@ToString
	static class Library implements Comparable<Library>, Serializable {
		private static final long serialVersionUID = 0;
		final String group;
		final String name;
		final String artifact;
		final Version version;
		final String url;
		final String file;
		final String buildTime;
		transient final File source;

		String getSimplePath() {
			return group.replace('.', '/') + '/' + name + '-' + artifact;
		}

		String getMavenPath() {
			return group.replace('.', '/') + '/' + name + '-' + version + '/' +
				name + '-' + version + '-' + (artifact == null ? "" : '-' + artifact);
		}

		String getKey() {
			return group + '.' + name;
		}

		private static boolean shouldUpgrade(boolean exists, Version previous, Version now) {
			// No jar
			if (!exists)
				return true;

			// Jar exists, but snapshot/beta
			if (now.suffixInt() < 0)
				// If not exact match (different URL or different timestamp) update
				return now.equals(previous);

			// TODO: checksum validation? can't validate checksums for snapshots as they will change.
			return false;
		}

		@SneakyThrows
		URL save(File extractionDir) {
			val path = getMavenPath();
			val lastPath = new File(extractionDir,  path + ".ll");
			val jarPath = new File(extractionDir, path + ".jar");
			Library last = null;
			try (val ois = new ObjectInputStream(new FileInputStream(lastPath))) {
				last = (Library) ois.readObject();
			} catch (Throwable ignored) {
			}
			if (!jarPath.exists() || last == null || !last.equals(this)) {
				if (file != null) {
					try (val zis = new ZipInputStream(new FileInputStream(source))) {
						ZipEntry e;
						while ((e = zis.getNextEntry()) != null) {
							if (!e.getName().equals(file))
								continue;
							Files.copy(zis, jarPath.toPath());
							break;
						}
					}
				} else if (url != null) {
					// TODO: dl from url
					throw new UnsupportedOperationException("not implemented");
				} else {
					throw new Error("No way to acquire dependency: " + this);
				}

				try (val oos = new ObjectOutputStream(new FileOutputStream(lastPath))) {
					oos.writeObject(this);
				}
			}
			return jarPath.toURI().toURL();
		}

		@Override
		public int compareTo(Library o) {
			val c = version.compareTo(o.version);
			if (c != 0)
				return c;
			return Long.compare(Long.parseLong(buildTime), Long.parseLong(o.buildTime));
		}
	}

	static class Version implements Comparable<Version>, Serializable {
		private static final long serialVersionUID = 0;
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

		static Version of(String s) {
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
