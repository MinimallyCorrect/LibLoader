package me.nallar.libloader;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.SneakyThrows;
import lombok.val;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sun.misc.URLClassPath;

import java.io.*;
import java.net.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

@IFMLLoadingPlugin.Name("LibLoader")
public class LibLoader implements IFMLLoadingPlugin {
	private static final Logger log = LogManager.getLogger("LibLoader");
	private static final AtomicBoolean inited = new AtomicBoolean();

	public LibLoader() {
		init();
	}

	@SuppressWarnings("WeakerAccess")
	public static void init() {
		if (!inited.compareAndSet(false, true))
			return;

		val mods = new File(System.getProperty("LibLoader.modsFolder", "mods/"));
		val libraries = new File(System.getProperty("LibLoader.librariesFolder", "libraries/"));
		val state = new File(libraries, "libloader mod state.obj");
		val libLoaderJar = new File(mods, System.getProperty("LibLoader.coreModJar", "   LibLoader.jar"));
		val tempDeleteMe = new File(libLoaderJar.getParentFile(), libLoaderJar.getName() + "-delete-me.tmp");
		if (tempDeleteMe.exists() && !tempDeleteMe.delete())
			tempDeleteMe.deleteOnExit();

		List<FileState> lastStates = LibLoader.readFromFile(state);
		List<FileState> newStates = new ArrayList<>();
		search(newStates, mods, 0);
		saveToFile(newStates, state);

		val anyChanges = !libLoaderJar.exists() || !Objects.equals(newStates, lastStates);
		if (anyChanges) {
			if (checkForNewerLibLoader(mods, libLoaderJar, tempDeleteMe)) {
				delete(state);
				delete(libLoaderJar);
				delete(tempDeleteMe);
				return;
			}
		}

		launch(anyChanges);
		saveToFile(newStates, state);
	}

	private static void delete(File f) {
		if (f.isFile() && !f.delete())
			f.deleteOnExit();
	}

	private static void launch(boolean anyChanges) {
		System.setProperty("LibLoader.anyChanges", String.valueOf(anyChanges));
		LibLoaderChained.init();
	}

	@SneakyThrows
	private static boolean checkForNewerLibLoader(File mods, File libLoaderJar, File tempDeleteMe) {
		val files = mods.listFiles();
		if (files == null)
			throw new FileNotFoundException(mods.getAbsolutePath());

		Version currentVersion = null;
		Version bestVersion = null;
		File bestFile = null;
		for (File file : files) {
			val name = file.getName().toLowerCase();
			if (!name.endsWith(".jar") && !name.endsWith(".zip"))
				continue;

			try (val fs = FileSystems.newFileSystem(file.toPath(), null)) {
				try {
					val version = new Version(new String(Files.readAllBytes(fs.getPath("LibLoader.version")), Charset.forName("UTF-8")));
					if (name.equalsIgnoreCase(libLoaderJar.getName())) {
						currentVersion = version;
						continue;
					}
					val libLoader = fs.getPath("LibLoader.jar");
					if (!Files.exists(libLoader)) {
						log.warn("Found LibLoader version '" + version + "' in '" + file + "' but no LibLoader.jar");
						continue;
					}
					if (bestVersion == null || version.compareTo(bestVersion) > 0) {
						bestVersion = version;
						bestFile = file;
					}
				} catch (IOException ignored) {
				} catch (Throwable t) {
					log.error("Failed to check LibLoader version in '" + file + '\'', t);
				}
			}
		}

		boolean update = bestVersion != null && (currentVersion == null || bestVersion.compareTo(currentVersion) > 0);
		boolean delete = libLoaderJar.exists() && (update || bestVersion == null);
		if (delete) {
			changeClassLoaderUrls(libLoaderJar, true);
			Files.move(libLoaderJar.toPath(), tempDeleteMe.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			delete(tempDeleteMe);
			delete(tempDeleteMe);
		}

		if (update) {
			try (val fs = FileSystems.newFileSystem(bestFile.toPath(), null)) {
				val libLoader = fs.getPath("LibLoader.jar");
				Files.copy(libLoader, libLoaderJar.toPath());
			}
			changeClassLoaderUrls(libLoaderJar, false);
			delete(tempDeleteMe);
		}

		return delete;
	}

	@SneakyThrows
	private static void changeClassLoaderUrls(File libLoaderJar, boolean remove) {
		val classLoader = (URLClassLoader) LibLoader.class.getClassLoader();
		val ucpField = URLClassLoader.class.getDeclaredField("ucp");
		ucpField.setAccessible(true);
		val oldUcp = (URLClassPath) ucpField.get(classLoader);
		val urls = new ArrayList<URL>();
		val libLoaderUrl = libLoaderJar.toURI().toURL();
		if (!remove)
			urls.add(libLoaderUrl);
		for (URL url : classLoader.getURLs()) {
			if (!urls.contains(url))
				urls.add(url);
		}
		if (remove)
			if (!urls.remove(libLoaderUrl))
				log.error("Failed to remove " + libLoaderUrl + " from urls: " + urls);
		ucpField.set(classLoader, new URLClassPath(urls.toArray(new URL[0])));
		oldUcp.closeLoaders();
	}

	private static <T> void saveToFile(T toSave, File f) {
		try {
			//noinspection ResultOfMethodCallIgnored
			f.getParentFile().mkdirs();
			val temp = new File(f.getParentFile(), f.getName() + ".temp");
			try (val os = new ObjectOutputStream(new FileOutputStream(temp))) {
				os.writeObject(toSave);
			}
			Files.move(temp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException ignored) {
		} catch (Throwable t) {
			log.error("Failed to save '" + f + '\'', t);
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T readFromFile(File f) {
		try {
			try (val is = new ObjectInputStream(new FileInputStream(f))) {
				return (T) is.readObject();
			}
		} catch (IOException ignored) {
		} catch (Throwable t) {
			log.error("Failed to read '" + f + '\'', t);
		}
		return null;
	}

	@SneakyThrows
	private static void search(List<FileState> fileStates, File directory, int depth) {
		if (depth > 20) {
			throw new IllegalArgumentException(directory + " depth too high: " + depth);
		}

		val files = directory.listFiles();
		if (files == null)
			throw new IOException(directory + " is not a directory");

		for (val f : files) {
			val name = f.getName();
			val lName = name.toLowerCase();
			if (f.isDirectory()) {
				if (depth == 0 && !("mods".equals(lName) || "libraries".equals(lName)))
					continue;
				search(fileStates, f, depth + 1);
				continue;
			}

			if (lName.endsWith(".jar") || lName.endsWith(".jlib") || lName.endsWith(".zip"))
				fileStates.add(new FileState(f));
		}

		fileStates.sort(Comparator.comparing(a -> a.path));
	}

	@Override
	public String[] getASMTransformerClass() {
		return new String[0];
	}

	@Override
	public String getModContainerClass() {
		return null;
	}

	@Override
	public String getSetupClass() {
		return null;
	}

	@Override
	public void injectData(Map<String, Object> var1) {
	}

	@Override
	public String getAccessTransformerClass() {
		return null;
	}

	@AllArgsConstructor
	@EqualsAndHashCode
	private static class FileState implements Serializable {
		private String path;
		private long time;
		private long size;

		@SneakyThrows
		FileState(File f) {
			path = f.getCanonicalPath();
			time = f.lastModified() / 1000L;
			size = f.length();
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
