package warbaby.compare_jar;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Hello world!
 *
 */
public class App 
{
    public static void main( String[] args ) throws IOException
    {
    	if(args.length!=2) {
    		System.out.println("Usage: jar1 jar2");
    		return;
    	}
    	
        final Path zip1 = FilesEx.getPath(args[0]);
        final Path zip2 = FilesEx.getPath(args[1]);
        
        Files.walkFileTree(zip1, new SimpleFileVisitor<Path>() {

			@Override
			public FileVisitResult visitFile(Path file1,
					BasicFileAttributes attrs) throws IOException {
				Path file2 = zip2.resolve(file1.toString());
				if(Files.exists(file2)) {
					if(!FilesEx.isFileBinaryEqual(file1, file2)) {
						System.out.println(file1.toString());
						FilesEx.copy(file1, Paths.get("diffs").resolve(file1.toString()));
					}
				}
				return FileVisitResult.CONTINUE;
			}
        	
        });
    }
}
