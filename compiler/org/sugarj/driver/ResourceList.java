package org.sugarj.driver;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * copied from http://forums.devx.com/showthread.php?t=153784
 * list resources available from the classpath
 * 
 * @author stoughto
 * 
 * modified by seba
 * 
 */
public class ResourceList {


  /**
   * for all elements of java.class.path get a Collection of
   * resources Pattern pattern = Pattern.compile(".*"); gets all
   * resources
   * 
   * @param pattern
   *        the pattern to match
   * @return the resources in the order they are found
   */
  public static List<URL> getResources(String pattern,
                                                List<String> paths,
                                                String ext) {
    ArrayList<URL> retval = new ArrayList<URL>();

    String patterns[] = pattern.split("/");
    
    for (String element : paths)
      retval.addAll(getResources(element, pattern, patterns, 0, ext));

    return retval;
  }

  private static List<URL> getResources(String element,
      String pattern, String[] patterns, int pos, String ext) {
    ArrayList<URL> retval = new ArrayList<URL>();
    File file = new File(element);
    if (file.isDirectory()) {
      retval.addAll(getResourcesFromFile(file, patterns, pos, ext));
    } else {
      retval.addAll(getResourcesFromJarFile(file, pattern, ext));
    }
    return retval;
  }

  private static List<URL> getResourcesFromJarFile(File file,
      String pattern, String ext) {
    ArrayList<URL> retval = new ArrayList<URL>();
    ZipFile zf;
    try {
      zf = new ZipFile(file);
    } catch (ZipException e) {
      throw new Error(e);
    } catch (IOException e) {
      throw new Error(e);
    }
    
    Enumeration<? extends ZipEntry> e = zf.entries();
    while (e.hasMoreElements()) {
      ZipEntry ze = e.nextElement();
      String fileName = ze.getName();
      
      // XXX not yet tested
      System.out.println(fileName);
      
      // XXX filter extension
      
      boolean accept = fileName.matches(pattern);
      if (accept) {
        try {
          retval.add(new URL("jar:file", file.getCanonicalPath(), fileName));
        } catch (IOException e1) {
          e1.printStackTrace();
        }
      }
    }
    try {
      zf.close();
    } catch (IOException e1) {
      throw new Error(e1);
    }
    return retval;
  }

  private static List<URL> getResourcesFromFile(File infile,
      String[] pattern, int pos, String ext) {
    ArrayList<URL> retval = new ArrayList<URL>();
    
    if (pos >= pattern.length)
    {
      if (ext.equals("*") || (FileCommands.getExtension(infile) != null && FileCommands.getExtension(infile).equals(ext)))
        try {
          retval.add(infile.toURI().toURL());
        } catch (IOException e) {
          e.printStackTrace();
        }
      
      return retval;
    }
    
    File[] fileList = infile.listFiles();
    for (File file : fileList)
      if (pattern[pos].equals("*") || file.getName().equals(pattern[pos]))
        retval.addAll(getResourcesFromFile(file, pattern, pos + 1, ext));

    return retval;
  }

}