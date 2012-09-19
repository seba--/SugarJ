package org.sugarj.driver;

import static org.sugarj.common.FileCommands.toWindowsPath;
import static org.sugarj.common.Log.log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

import org.spoofax.interpreter.core.InterpreterException;
import org.spoofax.interpreter.library.IOAgent;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr_layout.client.InvalidParseTableException;
import org.spoofax.jsglr_layout.client.SGLR;
import org.spoofax.jsglr_layout.client.imploder.IToken;
import org.spoofax.jsglr_layout.client.imploder.ImploderAttachment;
import org.spoofax.jsglr_layout.shared.BadTokenException;
import org.spoofax.jsglr_layout.shared.SGLRException;
import org.spoofax.jsglr_layout.shared.TokenExpectedException;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.StrategoException;
import org.strategoxt.lang.StrategoExit;
import org.strategoxt.strj.main_strj_0_0;
import org.sugarj.LanguageLib;
import org.sugarj.common.ATermCommands;
import org.sugarj.common.CommandExecution;
import org.sugarj.common.Environment;
import org.sugarj.common.FileCommands;
import org.sugarj.common.JavaCommands;
import org.sugarj.common.path.AbsolutePath;
import org.sugarj.common.path.Path;
import org.sugarj.common.path.RelativePath;
import org.sugarj.driver.caching.ModuleKey;
import org.sugarj.driver.caching.ModuleKeyCache;
import org.sugarj.driver.transformations.extraction.extract_str_0_0;
import org.sugarj.stdlib.StdLib;

/**
 * This class provides methods for various SDF commands. Each
 * SDF command is represented by a separate method of a similar
 * name.
 * 
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class STRCommands {
  
  
  private final static Pattern STR_FILE_PATTERN = Pattern.compile(".*\\.str");

  /**
   *  Compiles a {@code *.str} file to a single {@code *.java} file. 
   */
  private static void strj(Path str, Path java, String main, Context strjContext, Collection<Path> paths, LanguageLib langLib) throws IOException {
    
    /*
     * We can include as many paths as we want here, checking the
     * adequacy of the occurring imports is done elsewhere.
     */
    List<String> cmd = new ArrayList<String>(Arrays.asList(new String[] {
        "-i", toWindowsPath(str.getAbsolutePath()),
        "-o", toWindowsPath(java.getAbsolutePath()),
        "-m", main,
        "-p", "sugarj",
        "--library",
        "-O", "0"
    }));
    
    cmd.add("-I");
    cmd.add(StdLib.stdLibDir.getPath());
    cmd.add("-I");
    cmd.add(langLib.getLibraryDirectory().getPath());

    
    for (Path path : paths)
      if (path.getFile().isDirectory()){
        cmd.add("-I");
        cmd.add(path.getAbsolutePath());
      }
    
    
    final ByteArrayOutputStream log = new ByteArrayOutputStream();

    try {
      // XXX strj does not create Java file with non-fresh context
      Context c = org.strategoxt.strj.strj.init();
      
      c.setIOAgent(new IOAgent() {
        private final PrintStream err = new PrintStream(log, true);
        private final Writer errWriter = new org.sugarj.util.PrintStreamWriter(err);
        
        public Writer getWriter(int fd) {
            if (fd == CONST_STDERR)
              return errWriter; 
            else 
              return super.getWriter(fd);
        }
        
        public OutputStream internalGetOutputStream(int fd) {
            if (fd == CONST_STDERR)
              return err; 
            else 
              return super.internalGetOutputStream(fd);
        }
      });
      
      c.invokeStrategyCLI(main_strj_0_0.instance, "strj", cmd.toArray(new String[cmd.size()]));
    }
    catch (StrategoExit e) {
      if (e.getValue() != 0)
        throw new StrategoException("STRJ failed", e);
    } finally {
      if (log.size() > 0 && !log.toString().contains("Compilation succeeded"))
        throw new StrategoException(log.toString());

    }
  }
  
  
  public static Path compile(Path str,
                              String main,
                              Collection<Path> dependentFiles,
                              SGLR strParser,
                              Context strjContext,
                              ModuleKeyCache<Path> strCache,
                              Environment environment,
                              LanguageLib langLib) throws IOException,
                                                          InvalidParseTableException,
                                                          TokenExpectedException,
                                                          BadTokenException,
                                                          SGLRException {
    ModuleKey key = getModuleKeyForAssimilation(str, main, dependentFiles, strParser);
    Path prog = lookupAssimilationInCache(strCache, key);
    
    if (prog == null) {
      try {
        prog = generateAssimilator(key, str, main, strjContext, environment.getIncludePath(), langLib);
      } catch (StrategoException e) {
        prog = new AbsolutePath("error: " +e.getMessage());
      } finally {
        cacheAssimilator(strCache, key, prog, environment);
      }
    }
    
    if (prog.getAbsolutePath().startsWith("error:"))
      throw new StrategoException(prog.getAbsolutePath());
    
    return prog;
  }
    
  private static Path generateAssimilator(ModuleKey key,
                                          Path str,
                                          String main,
                                          Context strjContext,
                                          Collection<Path> paths,
                                          LanguageLib langLib) throws IOException {
    boolean success = false;
    log.beginTask("Generating", "Generate the assimilator");
    try {
      Path dir = FileCommands.newTempDir();
      FileCommands.createDir(new RelativePath(dir, "sugarj"));
      String javaFilename = FileCommands.fileName(str).replace("-", "_");
      Path java = new RelativePath(dir, "sugarj" + Environment.sep + javaFilename + ".java");
      log.log("calling STRJ");
      strj(str, java, main, strjContext, paths, langLib);
      
      if (!JavaCommands.javac(java, dir, paths))
        throw new RuntimeException("java compilation failed");
        
      Path jarfile = FileCommands.newTempFile("jar");
      JavaCommands.jar(dir, jarfile);

      FileCommands.deleteTempFiles(dir);
      FileCommands.deleteTempFiles(java);

      success = jarfile != null;
      return jarfile;
    } finally {
      log.endTask(success);
    }
  }
    
  private static void cacheAssimilator(ModuleKeyCache<Path> strCache, ModuleKey key, Path prog, Environment environment) throws IOException {
    if (strCache == null)
      return;
    

    log.beginTask("Caching", "Cache assimilator");
    try {
      Path cacheProg = environment.createCachePath(prog.getFile().getName());
      if (FileCommands.exists(prog))
        FileCommands.copyFile(prog, cacheProg);
      else
        cacheProg = prog;
      
      Path oldProg = strCache.putGet(key, cacheProg);
      FileCommands.delete(oldProg);

      if (CommandExecution.CACHE_INFO)
        log.log("Cache Location: " + cacheProg);
    } finally {
      log.endTask();
    }
  }
  
  private static Path lookupAssimilationInCache(ModuleKeyCache<Path> strCache, ModuleKey key) {
    if (strCache == null)
      return null;
    
    Path result = null;
    
    log.beginTask("Searching", "Search assimilator in cache");
    try {
      result = strCache.get(key);
      
      if (result == null || !result.getFile().exists())
        return null;

      if (CommandExecution.CACHE_INFO)
        log.log("Cache location: '" + result + "'");
      
      return result;
    } finally {
      log.endTask(result != null);
    }
  }


  private static ModuleKey getModuleKeyForAssimilation(Path str, String main, Collection<Path> dependentFiles, SGLR strParser) throws IOException, InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    log.beginTask("Generating", "Generate module key for current assimilation");
    try {
      IStrategoTerm aterm = (IStrategoTerm) strParser.parse(FileCommands.readFileAsString(str), str.getAbsolutePath(), "StrategoModule");

      aterm = ATermCommands.getApplicationSubterm(aterm, "Module", 1);

      LinkedList<Path> depList = new LinkedList<Path>();
      for (Path file : dependentFiles)
        if (STR_FILE_PATTERN.matcher(file.getAbsolutePath()).matches() && FileCommands.exists(file))
          depList.add(file);
      
      return new ModuleKey(depList, aterm);
    } catch (Exception e) {
      throw new SGLRException(strParser, "could not parse STR file " + str, e);
    } finally {
      log.endTask();
    }
    
  }

  public static IStrategoTerm assimilate(Path jarfile, IStrategoTerm in, HybridInterpreter interp) throws IOException {
    return assimilate("internal-main", jarfile, in, interp);
  }
  
  public static IStrategoTerm assimilate(String strategy, Path jarfile, IStrategoTerm in, HybridInterpreter interp) throws IOException {
    try {
      // XXX try release loaded classes by creating a completely new interpreter
      HybridInterpreter newInterp = new HybridInterpreter(interp.getFactory(), interp.getProgramFactory());
      newInterp.loadJars(jarfile.getFile().toURI().toURL());
      newInterp.setCurrent(in);
      
      if (newInterp.invoke(strategy)) {
        IStrategoTerm term = newInterp.current();
        
        // XXX does this improve memory consumption?
        newInterp.reset();
        
        IToken left = ImploderAttachment.getLeftToken(in);
        IToken right = ImploderAttachment.getRightToken(in);
        String sort = ImploderAttachment.getSort(in);
        
        try {
          term = ATermCommands.makeMutable(term);
          ImploderAttachment.putImploderAttachment(term, false, sort, left, right);
        } catch (Exception e) {
          log.log("origin annotation failed");
        }
        return term;
      }
      else
        throw new RuntimeException("hybrid interpreter failed");
    }
    catch (InterpreterException e) {
      throw new StrategoException("desugaring failed: " + e.getCause().getMessage(), e);
    }
    catch (Exception e) {
      throw new StrategoException("desugaring failed", e);
    }
  }
  
  /**
   * Filters Stratego statements from the given term
   * and compiles assimilation statements to Stratego.
   * 
   * @param term a file containing a list of SDF 
   *             and Stratego statements.
   * @param str result file
   * @throws InvalidParseTableException 
   */
  public static IStrategoTerm extractSTR(IStrategoTerm term, Context context) throws IOException, InvalidParseTableException {
    IStrategoTerm result = null;
    try {
      result = extract_str_0_0.instance.invoke(context, term);
    }
    catch (StrategoExit e) {
      if (e.getValue() != 0 || result == null)
        throw new RuntimeException("Stratego extraction failed", e);
    }
    return result;
  }
  
}
