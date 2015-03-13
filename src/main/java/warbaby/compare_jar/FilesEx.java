package warbaby.compare_jar;

import com.sun.nio.zipfs.ZipFileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 补充 java.nio.Files 里没有的方法
 *
 * @author WangBei
 * @since 2015/2/4
 */
public class FilesEx {

	public enum DirCopyOption implements CopyOption {
		/**
		 * if target directory exist, clean it first.
		 */
		REMOVE_FIRST
	}

	public static class MultiPathMatcher implements PathMatcher {
		private PathMatcher[] matchers;

		public MultiPathMatcher(PathMatcher ... matchers) {
			this.matchers = matchers;
		}

		@Override
		public boolean matches(Path path) {
			for (PathMatcher matcher : matchers) {
				if(matcher.matches(path)) return true;
			}
			return false;
		}
	}

    public static boolean isEmpty(Path path) {
        if(Files.isDirectory(path)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
                return !stream.iterator().hasNext();
            } catch (IOException e) {
                System.err.println("Path " + path.toString() + " is not directory");
            }
        }
        return false;
    }

	/**
	 * Compare two file as binary.
	 */
	public static boolean isFileBinaryEqual(Path f1, Path f2) {
		try {
			try (SeekableByteChannel c1 = Files.newByteChannel(f1, StandardOpenOption.READ)) {
				try (SeekableByteChannel c2 = Files.newByteChannel(f2, StandardOpenOption.READ)) {
					if (c1.size() != c2.size()) return false;
					ByteBuffer b1 = ByteBuffer.allocate(1024);
					ByteBuffer b2 = ByteBuffer.allocate(1024);
					while (true) {
						int len = c1.read(b1);
						if (len == -1) return true;
						c2.read(b2);
						b1.flip();
						b2.flip();
						for (int i = 0; i < len; i++) {
							if (b1.get() != b2.get())
								return false;
						}
					}
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Can't compare files " + f1 + " <-> " + f2, e);
		}
	}

	/**
	 * copy source to target, avoiding NoSuchFile exception.
	 */
	public static Path copy(Path source, Path target) throws IOException {
        if (target.getParent() != null && !Files.exists(target.getParent())) Files.createDirectories(target.getParent());
		return Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
	}

    /**
     * compare and copy if necessary
     */
    public static Path copyIfNecessary(Path source, Path target) throws IOException {
        if(!Files.exists(target) || !FilesEx.isFileBinaryEqual(source, target)) {
            if (target.getParent() != null && !Files.exists(target.getParent())) Files.createDirectories(target.getParent());
            return Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES, StandardCopyOption.REPLACE_EXISTING);
        } else {
            return target;
        }
    }

	/**
	 * recursively delete directory using nio.
	 * @param target directory to delete.
	 * @throws IOException
	 */
	public static void deleteTree(Path target) throws IOException {
		if(!Files.exists(target)) return;
		Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.delete(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
				if(!(dir.getFileSystem() instanceof ZipFileSystem) || !Files.isSameFile(dir, dir.getFileSystem().getPath("/")))
					Files.delete(dir);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	public static List<String> copyTree(Path source, Path target, CopyOption... options) throws IOException {
		return copyTree(source, target, null, options);
	}
	/**
	 * recursively copy directory using nio.
	 * @param source source directory
	 * @param target target directory
	 * @param includedMatcher only copy include file
	 * @param options REMOVE_FIRST, REPLACE_EXISTING
	 * @throws IOException
	 */
	public static List<String> copyTree(final Path source, final Path target, final PathMatcher includedMatcher, CopyOption... options) throws IOException {
		boolean removeFirst = false;
		boolean overwrite = false;
		for(CopyOption option : options) {
			if (option == DirCopyOption.REMOVE_FIRST) {
				removeFirst = true;
			} else if (option == StandardCopyOption.REPLACE_EXISTING) {
				overwrite = true;
			}
		}
		if(removeFirst && Files.exists(target)) {
			deleteTree(target);
		}
		final List<String> copied = new ArrayList<>();
		final boolean finalOverwrite = overwrite;
		Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				String relative = source.relativize(file).toString();
				Path targetFile = target.resolve(relative);
				if(finalOverwrite || !Files.exists(targetFile)) {
					if (includedMatcher == null || includedMatcher.matches(file.getFileName())) {
						copy(file, targetFile);
						copied.add(relative);
					}
				}
				return FileVisitResult.CONTINUE;
			}
		});
		return copied;
	}

	//--------------------------------------------------------------
	// ZipFileSystem related.
	//--------------------------------------------------------------
	/**
	 * Open or create zip file as a ZipFileSystem.
	 * @throws IOException
	 */
	public static FileSystem createZipFS(Path zipFile) throws IOException {
		if(Files.exists(zipFile)) {
			return FileSystems.newFileSystem(zipFile, null);
		} else {
			HashMap<String, Object> env = new HashMap<>();
			env.put("create", "true");
			return FileSystems.newFileSystem(URI.create("jar:" + zipFile.toUri()), env);
		}
	}

	public static final Pattern ZIP_PATH_PATTERN = Pattern.compile("\\.(zip|jar)(!/.*)?$", Pattern.CASE_INSENSITIVE);

	/**
	 * supporting 'abc.zip!/root/...' or 'abc.jar!/...'.
	 * Remember to close the ZipFileSystem!
	 */
	public static Path getPath(String path) {
		Matcher matcher = ZIP_PATH_PATTERN.matcher(path);
		if(matcher.find()) {
			try {
				String zipFile = path.substring(0, matcher.start() + 4);
				String pathInZip = path.substring(matcher.start() + 4);
				pathInZip = pathInZip.isEmpty() ? "/" : pathInZip.substring(1);
                Path zipFilePath = Paths.get(zipFile);
                if(Files.exists(zipFilePath) && Files.isDirectory(zipFilePath)) {
                    return zipFilePath;
                }
                FileSystem zfs = FileSystems.newFileSystem(zipFilePath, null);
				return zfs.getPath(pathInZip);
			} catch (IOException e) {
				throw new RuntimeException("Error opening zip file system.", e);
			}
		} else {
			return Paths.get(path);
		}
	}

	public static void close(Path path) throws IOException {
		if(path.getFileSystem() instanceof ZipFileSystem) {
			path.getFileSystem().close();
		}
	}

	public static void close(FileSystem fs) throws IOException {
		if(fs instanceof ZipFileSystem) {
			fs.close();
		}
	}
}
