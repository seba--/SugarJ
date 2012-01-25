package org.sugarj.driver;

import static org.sugarj.driver.ATermCommands.extractSDF;
import static org.sugarj.driver.ATermCommands.extractSTR;
import static org.sugarj.driver.ATermCommands.fixSDF;
import static org.sugarj.driver.ATermCommands.getApplicationSubterm;
import static org.sugarj.driver.ATermCommands.getList;
import static org.sugarj.driver.ATermCommands.getString;
import static org.sugarj.driver.ATermCommands.isApplication;
import static org.sugarj.driver.Environment.sep;
import static org.sugarj.driver.Log.log;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.ParseException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.runtime.IProgressMonitor;
import org.spoofax.interpreter.terms.IStrategoTerm;
import org.spoofax.jsglr.client.InvalidParseTableException;
import org.spoofax.jsglr.client.ParseTable;
import org.spoofax.jsglr.client.imploder.IToken;
import org.spoofax.jsglr.client.imploder.ImploderAttachment;
import org.spoofax.jsglr.shared.BadTokenException;
import org.spoofax.jsglr.shared.SGLRException;
import org.spoofax.jsglr.shared.TokenExpectedException;
import org.spoofax.terms.Term;
import org.strategoxt.HybridInterpreter;
import org.strategoxt.imp.runtime.parser.JSGLRI;
import org.strategoxt.lang.Context;
import org.strategoxt.lang.StrategoException;
import org.strategoxt.permissivegrammars.make_permissive;
import org.strategoxt.tools.tools;
import org.sugarj.driver.caching.ModuleKey;
import org.sugarj.driver.caching.ModuleKeyCache;
import org.sugarj.driver.cli.CLIError;
import org.sugarj.driver.cli.DriverCLI;
import org.sugarj.driver.path.AbsolutePath;
import org.sugarj.driver.path.Path;
import org.sugarj.driver.path.RelativePath;
import org.sugarj.driver.path.RelativeSourceLocationPath;
import org.sugarj.driver.path.SourceLocation;
import org.sugarj.driver.transformations.extraction.extraction;
import org.sugarj.stdlib.StdLib;
import org.sugarj.util.ProcessingListener;
import org.sugarj.util.ToplevelDeclarationProvider;

/**
 * @author Sebastian Erdweg <seba at informatik uni-marburg de>
 */
public class Driver{
  
  public final static String CACHE_VERSION = "model-0.1d";
  
  private final static int PENDING_TIMEOUT = 30000;

  private static Map<Path, Result> resultCache = new HashMap<Path, Result>(); // new LRUMap(50);
  private static Map<Path, Entry<ToplevelDeclarationProvider, Driver>> pendingRuns = new HashMap<Path, Map.Entry<ToplevelDeclarationProvider,Driver>>();

  private static List<RelativeSourceLocationPath> allInputFiles = new ArrayList<RelativeSourceLocationPath>();
  private static List<Path> pendingInputFiles = new ArrayList<Path>();
  
  private static List<Path> currentlyProcessing = new ArrayList<Path>();

  private static List<ProcessingListener> processingListener = new LinkedList<ProcessingListener>();
  
  
  private IProgressMonitor monitor;
  
  private Environment environment = new Environment();
  
  private Result driverResult;
  
  private Path javaOutFile;
  private Path depOutFile;
  private String relPackageName;
  private RelativeSourceLocationPath sourceFile;

  private Path currentGrammarSDF;
  private String currentGrammarModule;
  private Path currentTransSTR;
  private String currentTransModule;
  private List<RelativePath> availableSDFImports;
  private List<RelativePath> availableSTRImports;
  
  private IStrategoTerm sugaredPackageDecl;
  private IStrategoTerm desugaredPackageDecl;
  private List<IStrategoTerm> sugaredImportDecls = new ArrayList<IStrategoTerm>();
  private List<IStrategoTerm> desugaredImportDecls = new ArrayList<IStrategoTerm>();
  private List<IStrategoTerm> sugaredBodyDecls = new ArrayList<IStrategoTerm>();
  private List<IStrategoTerm> desugaredBodyDecls = new ArrayList<IStrategoTerm>();
  
  private IStrategoTerm lastSugaredToplevelDecl;
  
  private JSGLRI sdfParser;
  private JSGLRI strParser;
  private JSGLRI editorServicesParser;
  private HybridInterpreter interp;
  private Context sdfContext;
  private Context makePermissiveContext;
  private Context extractionContext;
  private Context strjContext;
  
  private ModuleKeyCache<Path> sdfCache = null;
  private ModuleKeyCache<Path> strCache = null;
  
  private Path currentGrammarTBL;
  private Path currentTransProg;
  
  private boolean interrupt = false;
  
  private boolean generateFiles;
  private Path delegateCompilation = null;
  
  private List<Path> generatedJavaClasses = new ArrayList<Path>();
  
  private ToplevelDeclarationProvider declProvider;

  private RetractableTreeBuilder inputTreeBuilder;
  
  public Driver(Environment env) {
    this.environment=env;
    
    try {      
      if (environment.getCacheDir() != null)
        FileCommands.createDir(environment.getCacheDir());
      
      FileCommands.createDir(environment.getBin());
      
      initializeCaches(environment, false);
    } catch (IOException e) {
      throw new RuntimeException("error while initializing driver", e);
    }
  }  
  
  
  private static synchronized Result getResult(Path file) {
    return (Result) resultCache.get(file);
  }
  
  private static synchronized Entry<ToplevelDeclarationProvider, Driver> getPendingRun(Path file) {
    return pendingRuns.get(file);
  }
  
  private static synchronized void putPendingRun(Path file, ToplevelDeclarationProvider source, Driver driver) {
    pendingRuns.put(file, new AbstractMap.SimpleImmutableEntry<ToplevelDeclarationProvider, Driver>(source, driver));
  }
  
  public static synchronized void addProcessingDoneListener(ProcessingListener listener) {
    processingListener.add(listener);
  }
  
  public static synchronized void removeProcessingDoneListener(ProcessingListener listener) {
    processingListener.remove(listener);
  }
  
  private static void waitForPending(Path file) {
    int count = 0;
    Object lock = new Object();
    synchronized (lock) {
      while (true) {
        synchronized (pendingRuns) {
          if (!pendingRuns.containsKey(file))
            return;
        }
        
        if (count > PENDING_TIMEOUT) {
          log.logErr("pending result timed out for " + file);
          synchronized (pendingRuns) {
            pendingRuns.remove(file);
          }
          return;
        }
        
        count += 100;
        try {
          lock.wait(100);
        } catch (InterruptedException e) {
        }
      }
    }
  }

  private static synchronized void putResult(Path file, Result result) {
    resultCache.put(file, result);
    Log.log.log(resultCache.size());
  }
  
  public static Result compile(RelativeSourceLocationPath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(sourceFile, monitor, true);
  }

  public static Result parse(RelativeSourceLocationPath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(sourceFile, monitor, false);
  }
  
  public static Result compile(String source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(source, sourceFile, monitor, true);
  }
  
  public static Result parse(String source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(source, sourceFile, monitor, false);
  }

  public static Result compile(IStrategoTerm source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(source, sourceFile, monitor, true);
  }

  public static Result parse(IStrategoTerm source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    return run(source, sourceFile, monitor, false);
  }

  private static Result run(RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, boolean generateFiles) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    if (generateFiles)
      synchronized (currentlyProcessing) {
        // TODO we need better circular dependency handling
        if (currentlyProcessing.contains(sourceFile))
          ;
          // throw new IllegalStateException("circular processing");
        currentlyProcessing.add(sourceFile);
      }

    Result res;
    
    try {
      String source = FileCommands.readFileAsString(sourceFile);
      res = run(source, sourceFile, monitor, generateFiles);
    } finally {
      if (generateFiles)
        synchronized (currentlyProcessing) {
          currentlyProcessing.remove(sourceFile);
        }
      pendingInputFiles.remove(sourceFile);
    }
    return res;
  }
  
  private static Result run(String source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, boolean generateFiles) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Driver driver = new Driver(sourceFile.getSourceLocation().getEnvironment());
    ToplevelDeclarationProvider declProvider = driver.new SourceCodeToplevelDeclarationProvider(source);
    return run(driver, declProvider, sourceFile, monitor, generateFiles);
  }
  
  private static Result run(IStrategoTerm source, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, boolean generateFiles) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Driver driver = new Driver(sourceFile.getSourceLocation().getEnvironment());
    ToplevelDeclarationProvider declProvider = driver.new TermToplevelDeclarationProvider(source);
    return run(driver, declProvider, sourceFile, monitor, generateFiles);
  }
  
  
  private static Result run(Driver driver, ToplevelDeclarationProvider declProvider, RelativeSourceLocationPath sourceFile, IProgressMonitor monitor, boolean generateFiles) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    Entry<ToplevelDeclarationProvider, Driver> pending = null;
    
    synchronized (Driver.class) {
      pending = getPendingRun(sourceFile);
      if (pending != null && !pending.getKey().equals(declProvider)) {
        log.log("interrupting " + sourceFile);
        pending.getValue().interrupt();
      }

      if (pending == null) {
        Result result = getResult(sourceFile);
        if (result != null && result.isUpToDate(declProvider.getSourceHashCode(), sourceFile.getSourceLocation().getEnvironment()))
          return result;
      }
      
      if (pending == null)
        putPendingRun(sourceFile, declProvider, driver);
    }
    
    if (pending != null) {
      waitForPending(sourceFile);
      return run(driver, declProvider, sourceFile, monitor, generateFiles);
    }
    
    try {
      synchronized (processingListener) {
        for (ProcessingListener listener : processingListener)
          listener.processingStarts(sourceFile);
      }
    
      driver.process(declProvider, sourceFile, monitor, generateFiles);
      if (!Environment.rocache)
        driver.storeCaches(sourceFile.getSourceLocation().getEnvironment());
    
      synchronized (processingListener) {
        for (ProcessingListener listener : processingListener)
          listener.processingDone(driver.driverResult);
      }

    } finally {
      pendingRuns.remove(sourceFile);
      if (generateFiles)
        putResult(sourceFile, driver.driverResult != null && driver.driverResult.getSugaredSyntaxTree() == null ? null : driver.driverResult);
    }

    return driver.driverResult;
  }
  
  /**
   * Process the given Extensible Java file.
   * 
   * @throws IOException 
   * @throws SGLRException 
   * @throws InvalidParseTableException 
   * @throws ParseException 
   * @throws BadTokenException 
   * @throws TokenExpectedException 
   * @throws InterruptedException 
   */
  private void process(ToplevelDeclarationProvider declProvider, RelativePath sourceFile, IProgressMonitor monitor, boolean generateFiles) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException, InterruptedException {
    this.monitor = monitor;
    log.beginTask("processing", "BEGIN PROCESSING " + sourceFile.getRelativePath());
    boolean success = false;
    try {
      init(declProvider, sourceFile, generateFiles);
      driverResult.setSourceFile(this.sourceFile, declProvider.getSourceHashCode());
      
      if (sourceFile != null) {
        // QST: delete this java File?
        javaOutFile = environment.new RelativePathBin(FileCommands.dropExtension(sourceFile.getRelativePath()) + ".java");
        depOutFile = environment.new RelativePathBin(FileCommands.dropExtension(sourceFile.getRelativePath()) + ".dep");
        Path genLog = environment.new RelativePathBin(FileCommands.dropExtension(sourceFile.getRelativePath()) + ".gen");
        driverResult.setGenerationLog(genLog);
//        clearGeneratedStuff();
      }

      initEditorServices();

      boolean done = false;
      while (!done) {
        stepped();
        
        
        // PARSE the next top-level declaration
        lastSugaredToplevelDecl = declProvider.getNextToplevelDecl(true);
       
        stepped();
        
        // DESUGAR the parsed top-level declaration
        IStrategoTerm desugared = declProvider.desugarToplevelDecl(lastSugaredToplevelDecl);
        
        stepped();
        
        // PROCESS the assimilated top-level declaration
        processToplevelDeclaration(desugared);

        done = !declProvider.hasNextToplevelDecl();
      }
      
      stepped();
            
      // check final grammar and transformation for errors
      if (!environment.isNoChecking() && generateFiles)
        checkCurrentGrammar();
      
      stepped();
      
      // need to build current transformation program for editor services
      // but: the newest STR code is not written regenerated while parsing
      if (generateFiles)
        checkCurrentTransformation();
      
      stepped();
      
      // COMPILE the generated java file
      if (delegateCompilation == null)
        compileGeneratedJavaFiles();
      else
        driverResult.delegateCompilation(delegateCompilation, javaOutFile, generatedJavaClasses);
      
       driverResult.setSugaredSyntaxTree(makeSugaredSyntaxTree());
      
      
      if (currentGrammarTBL != null)
        driverResult.registerParseTable(currentGrammarTBL);
      
      if (currentTransProg != null) {
        driverResult.addEditorService(
            ATermCommands.atermFromString(
              "Builders(\"sugarj checking\", [SemanticObserver(Strategy(\"sugarj-analyze\"))])"));
        driverResult.registerEditorDesugarings(currentTransProg);
      }
      
      driverResult.writeDependencyFile(depOutFile);

      success = true;
    } 
    catch (CommandExecution.ExecutionError e) {
      // TODO do something more sensible
      e.printStackTrace();
      success = false;
    }
    finally {
      log.endTask(success, "done processing " + sourceFile, "failed processing " + sourceFile);
      driverResult.setFailed(!success);
    }
  }

  private void compileGeneratedJavaFiles() throws IOException {
    boolean good = false;
    log.beginTask("compilation", "COMPILE the generated java file");
    try {
        driverResult.compileJava(javaOutFile, environment.getBin(), new ArrayList<Path>(environment.getIncludePath()), generatedJavaClasses);
      good = true;
    } finally {
      log.endTask(good, "compilation succeeded", "compilation failed");
    }
  }

  private void processEditorServicesDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask(
        "processing",
        "PROCESS the desugared editor services declaration.");
    try {
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
      
      String extName = null;
      String fullExtName = null;
      boolean isPublic = false;

      IStrategoTerm head = getApplicationSubterm(toplevelDecl, "EditorServicesDec", 0);
      IStrategoTerm body= getApplicationSubterm(toplevelDecl, "EditorServicesDec", 1);
      
      log.beginTask("Extracting name and accessibility of the editor services.");
      try {
        extName =
          SDFCommands.prettyPrintJava(
          getApplicationSubterm(head, "EditorServicesDecHead", 1), interp);    
        
        IStrategoTerm mods = getApplicationSubterm(head, "EditorServicesDecHead", 0);
        
        for (IStrategoTerm t : getList(mods))
          if (isApplication(t, "Public"))
          {
            isPublic = true;
            break;
          }
        
        fullExtName = relPackageNameSep() + extName;

        log.log("The name of the editor services is '" + extName + "'.");
        log.log("The full name of the editor services is '" + fullExtName + "'.");

        if (isPublic)
          log.log("The editor services is public.");
        else
          log.log("The editor services is not public.");
      
        IStrategoTerm services = ATermCommands.getApplicationSubterm(body, "EditorServicesBody", 0);
        
        if (!ATermCommands.isList(services))
          throw new IllegalStateException("editor services are not a list: " + services);
        
        List<IStrategoTerm> editorServices = ATermCommands.getList(services);
        
        if (currentTransProg == null)
          try {
            currentTransProg = STRCommands.compile(currentTransSTR, "main", driverResult.getFileDependencies(environment), strParser, strjContext, strCache, environment);
          } catch (SGLRException e) {
            log.logErr(e.toString());
          } catch (InvalidParseTableException e) {
            log.logErr(e.toString());
          }
        editorServices = ATermCommands.registerSemanticProvider(editorServices, currentTransProg);
  
        Path editorServicesFile = environment.new RelativePathBin(relPackageNameSep() + extName + ".serv");
        
        log.log("writing editor services to " + editorServicesFile);
        
        StringBuffer buf = new StringBuffer();
        
        for (IStrategoTerm service : driverResult.getEditorServices())
          buf.append(service).append('\n');
        
        for (IStrategoTerm service : editorServices) {
          driverResult.addEditorService(service);
          buf.append(service).append('\n');
        }
        
        driverResult.generateFile(editorServicesFile, buf.toString());
      } finally {
        log.endTask();
      }
    } finally {
      log.endTask();
    }
  }
  
  private void processPlainDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask(
        "processing",
        "PROCESS the desugared plain declaration.");
    try {
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);

      
      String extName = null;
      String fullExtName = null;
      boolean isPublic = false;

      IStrategoTerm head = getApplicationSubterm(toplevelDecl, "PlainDec", 0);
      IStrategoTerm body= getApplicationSubterm(toplevelDecl, "PlainDec", 1);
      
      log.beginTask("Extracting name and accessibility.");
      try {
        extName =
          SDFCommands.prettyPrintJava(
          getApplicationSubterm(head, "PlainDecHead", 1), interp);    

        String extension = null;
        if (head.getSubtermCount() >= 3 && isApplication(getApplicationSubterm(head, "PlainDecHead", 2), "Some"))
          extension = Term.asJavaString(
                      getApplicationSubterm(getApplicationSubterm(head, "PlainDecHead", 2), "Some", 0));    
 

        
        IStrategoTerm mods = getApplicationSubterm(head, "PlainDecHead", 0);
        
        for (IStrategoTerm t : getList(mods))
          if (isApplication(t, "Public"))
          {
            isPublic = true;
            break;
          }
        
        fullExtName = relPackageNameSep() + extName + (extension == null ? "" : ("." + extension));

        log.log("The name is '" + extName + "'.");
        log.log("The full name is '" + fullExtName + "'.");

        if (isPublic)
          log.log("The plain file is public.");
        else
          log.log("The plain file is not public.");
      
        String plainContent = Term.asJavaString(ATermCommands.getApplicationSubterm(body, "PlainBody", 0));
        
        String ext = extension == null ? "" : ("." + extension);
        Path plainFile = environment.new RelativePathBin(relPackageNameSep() + extName + ext);
        FileCommands.createFile(plainFile);
  
        log.log("writing plain content to " + plainFile);
        driverResult.generateFile(plainFile, plainContent);
      } finally {
        log.endTask();
      }
    } finally {
      log.endTask();
    }
  }
  
  
  private void processToplevelDeclaration(IStrategoTerm toplevelDecl)
      throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException {
    if (isApplication(toplevelDecl, "PackageDec"))
      processPackageDec(toplevelDecl);
    else {
      if (relPackageName == null)
        checkPackageName(toplevelDecl);
      if (javaOutFile == null)
        javaOutFile = environment.new RelativePathBin(relPackageNameSep() + FileCommands.fileName(driverResult.getSourceFile()) + ".java");
      if (depOutFile == null)
        depOutFile = environment.new RelativePathBin(relPackageNameSep() + FileCommands.fileName(driverResult.getSourceFile()) + ".dep");
      try {
        if (isApplication(toplevelDecl, "TypeImportDec") || isApplication(toplevelDecl, "TypeImportOnDemandDec") || isApplication(toplevelDecl, "TransImportDec")) {
          if (!environment.isAtomicImportParsing())
            processImportDec(toplevelDecl);
          else 
            toplevelDecl = processImportDecs(toplevelDecl);
        }
        else if (ATermCommands.isList(toplevelDecl))
          /* 
           * Desugarings may generate lists of toplevel declarations. These declarations,
           * however, may not depend on one another.
           */
          for (IStrategoTerm term : ATermCommands.getList(toplevelDecl))
            processToplevelDeclaration(term);
        else if (isApplication(toplevelDecl, "JavaTypeDec") || //XXX remove this branch
                 isApplication(toplevelDecl, "ClassDec") ||
                 isApplication(toplevelDecl, "InterfaceDec") ||
                 isApplication(toplevelDecl, "EnumDec") ||
                 isApplication(toplevelDecl, "AnnoDec"))
          processJavaTypeDec(toplevelDecl);
        else if (isApplication(toplevelDecl, "SugarDec"))
          processSugarDec(toplevelDecl);
        else if (isApplication(toplevelDecl, "EditorServicesDec")) 
          processEditorServicesDec(toplevelDecl);
        else if (isApplication(toplevelDecl, "PlainDec")) 
          processPlainDec(toplevelDecl);
        else if (isApplication(toplevelDecl, "ModelDec"))
            processModel(toplevelDecl);
        else if (ATermCommands.isString(toplevelDecl)) {
          if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
            sugaredBodyDecls.add(lastSugaredToplevelDecl);
        }
        else
          throw new IllegalArgumentException("unexpected toplevel declaration, desugaring probably failed: " + toplevelDecl.toString(5));
      } catch (Exception e) {
        handleException(e, toplevelDecl);
      } finally {
        if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
          sugaredBodyDecls.add(lastSugaredToplevelDecl);
      }
    }
  }

  private void processPackageDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask("processing", "PROCESS the desugared package declaration.");
    try {
      sugaredPackageDecl = lastSugaredToplevelDecl;
      desugaredPackageDecl = toplevelDecl;
      
      String packageName =
          SDFCommands.prettyPrintJava(
          getApplicationSubterm(toplevelDecl, "PackageDec", 1), interp);

      log.log("The Java package name is '" + packageName + "'.");

      relPackageName = FileCommands.getRelativeModulePath(packageName);

      log.log("The SDF / Stratego package name is '" + relPackageName + "'.");

      checkPackageName(toplevelDecl);
      
      if (javaOutFile == null)
        javaOutFile = environment.new RelativePathBin(relPackageNameSep() + FileCommands.fileName(driverResult.getSourceFile()) + ".java");
      if (depOutFile == null)
        depOutFile = environment.new RelativePathBin(relPackageNameSep() + FileCommands.fileName(driverResult.getSourceFile()) + ".dep");
      
      driverResult.generateFile(javaOutFile, SDFCommands.prettyPrintJava(toplevelDecl, interp) + "\n");
    } finally {
      log.endTask();
    }
  }
  
  private void checkPackageName(IStrategoTerm toplevelDecl) {
    if (sourceFile != null) {
      String packageName = relPackageName == null ? "" : relPackageName.replace('/', '.');
      
      String rel = FileCommands.dropExtension(sourceFile.getRelativePath());
      int i = rel.lastIndexOf('/');
      String expectedPackage = i >= 0 ? rel.substring(0, i) : rel;
      expectedPackage = expectedPackage.replace('/', '.');
      if (!packageName.equals(expectedPackage))
        setErrorMessage(
            toplevelDecl,
            "The declared package '" + packageName + "'" +
            " does not match the expected package '" + expectedPackage + "'.");
    }
  }
  
  private void processModel(IStrategoTerm toplevelDecl) throws IOException {
    if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);
    
    
    IStrategoTerm head = getApplicationSubterm(toplevelDecl, "ModelDec", 0);
    // IStrategoTerm bodyDec = getApplicationSubterm(toplevelDecl, "ModelDec", 1);
    
    desugaredBodyDecls.add(toplevelDecl);
    
    String modelName;
    log.beginTask("Extracting name and accessibility of the model.");
    try {
      modelName = SDFCommands.prettyPrintJava(getApplicationSubterm(head, "ModelDecHead", 1), interp);    
    } finally {
      log.endTask();
    }
    
    RelativePath modelOutFile = environment.new RelativePathBin(relPackageNameSep() + modelName + ".model");

    driverResult.generateFile(modelOutFile, ATermCommands.atermToString(makeDesugaredSyntaxTree()));
  }
  
  private IStrategoTerm processImportDecs(IStrategoTerm toplevelDecl) throws IOException, TokenExpectedException, BadTokenException, ParseException, InvalidParseTableException, SGLRException {
    List<IStrategoTerm> pendingImports = new ArrayList<IStrategoTerm>();
    pendingImports.add(toplevelDecl);
    
    while (true) {
      IStrategoTerm term = null;
      
      try {
        log.beginSilent();
        term = declProvider.getNextToplevelDecl(false);
      }
      catch (Throwable t) {
        term = null;
      }
      finally {         
        log.endSilent(); 
      }
    
      if (term != null &&
          (isApplication(term, "TypeImportDec") ||
           isApplication(term, "TypeImportOnDemandDec") ||
           isApplication(term, "TransImportDec"))) {
        pendingImports.add(term);
      }
      else {
        declProvider.retract(term);
        break;
      }
    }
    
    for (IStrategoTerm pendingImport : pendingImports) {
      lastSugaredToplevelDecl = pendingImport;
      try {
        processImportDec(pendingImport);
      } catch (Exception e) {
        handleException(e, pendingImport);
      }
    }
    
    return pendingImports.get(pendingImports.size() - 1);
  }

  //TODO handle import declarations with asterisks, e.g. import foo.*;
  private void processImportDec(IStrategoTerm toplevelDecl) {
    
    sugaredImportDecls.add(lastSugaredToplevelDecl);
    desugaredImportDecls.add(toplevelDecl);
    
    log.beginTask("processing", "PROCESS the desugared import declaration.");
    try {
      
      String importModule = ModuleSystemCommands.extractImportedModuleName(toplevelDecl, interp);
      List<String> importTransformations = ModuleSystemCommands.extractImportedTransformationNames(toplevelDecl, interp);
      IStrategoTerm stdImportTerm = ATermCommands.makeStdImport(toplevelDecl);
          
      String modulePath = FileCommands.getRelativeModulePath(importModule);

      List<String> transformationPaths;
      if (importTransformations == null)
        transformationPaths = null;
      else {
        transformationPaths= new ArrayList<String>();
        for (String importTransformation : importTransformations)
          transformationPaths.add(FileCommands.getRelativeModulePath(importTransformation));
      }
      
      Result res = null;
      Path dep = ModuleSystemCommands.searchFile(modulePath, ".dep", environment);

      boolean skipProcessImport = false;

      if (!modulePath.startsWith("org/sugarj")) {
        RelativeSourceLocationPath importSourceFile = null;
        
        if (dep != null) {
          try {
            res = Result.readDependencyFile(dep, environment);
          } catch (IOException e) {
            log.logErr("could not read dependency file " + dep);
          }
          
          if (res != null && res.getSourceFile() != null && res.getSourceFile().getBasePath().equals(environment.getRoot()))
            importSourceFile = res.getSourceFile();
        }
        
        if (importSourceFile == null)
          importSourceFile = ModuleSystemCommands.locateSourceFile(modulePath, environment.getSourcePath());

        if (importSourceFile != null && (res == null || pendingInputFiles.contains(res.getSourceFile()) || !res.isUpToDate(res.getSourceFile(), environment))) {
          if (!generateFiles) {
//            boolean b = res == null || !res.isUpToDate(res.getSourceFile(), environment);
//            System.out.println(b);
            setErrorMessage(toplevelDecl, "module outdated, compile first: " + importModule);
          }
          else {
              if (currentlyProcessing.contains(importSourceFile)) {
                // assume source file does not provide syntactic sugar
                driverResult.appendToFile(javaOutFile, "import " + importModule + ";\n");
                skipProcessImport = true;
                delegateCompilation = importSourceFile;
              }
              else {
                log.log("Need to compile the imported module first; processing it now.");

                try {
                  storeCaches(environment);
       
                  Result importResult = compile(importSourceFile, monitor);
                  initializeCaches(environment, true);
                  if (importResult.hasFailed())
                    setErrorMessage(toplevelDecl, "problems while compiling " + importModule);
                } catch (Exception e) {
                  setErrorMessage(toplevelDecl, "problems while compiling " + importModule);
                }
                  
                log.log("CONTINUE PROCESSING'" + importSourceFile + "'.");
              }
          }
        }
        
        if (dep == null)
          dep = ModuleSystemCommands.searchFile(modulePath, ".dep", environment);

        if (dep != null && !skipProcessImport)
          driverResult.addDependency(dep, environment);

        if (driverResult.hasDelegatedCompilation(importSourceFile)) {
          driverResult.appendToFile(javaOutFile, "import " + importModule + ";\n");
          skipProcessImport = true;
        }
      }
      
      boolean success = skipProcessImport || processImport(modulePath, res, dep, transformationPaths, stdImportTerm, false);
      
      if (!success)
        setErrorMessage(toplevelDecl, "module not found: " + importModule);
      
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      log.endTask();
    }
  }
  
  private boolean processImport(String modulePath, Result importedResult, Path importedResultPath, List<String> transformationPaths, IStrategoTerm importTerm, boolean modelRecursive) throws IOException {
    boolean success = false;
    
    boolean classImport = ModuleSystemCommands.importClass(
        modulePath, 
        importTerm, 
        javaOutFile,
        interp, 
        driverResult,
        environment);
    ModuleSystemCommands.registerSearchedClassFiles(modulePath, driverResult, environment);
    if (classImport) {
      success = true;
      driverResult.appendToFile(javaOutFile, SDFCommands.prettyPrintJava(importTerm, interp) + "\n");
    }

    RelativePath sdf = ModuleSystemCommands.importSdf(modulePath, environment);
    ModuleSystemCommands.registerSearchedSdfFiles(modulePath, driverResult, environment);
    if (sdf != null) {
      success = true;
      availableSDFImports.add(sdf);
      buildCompoundSdfModule();
    }
    
    RelativePath str = ModuleSystemCommands.importStratego(modulePath, environment);
    ModuleSystemCommands.registerSearchedStrategoFiles(modulePath, driverResult, environment);
    if (str != null) {
      success = true;
      availableSTRImports.add(str);
      buildCompoundStrModule();
    }
    
    success |= ModuleSystemCommands.importEditorServices(modulePath, driverResult, environment);
    ModuleSystemCommands.registerSearchedEditorServicesFiles(modulePath, driverResult, environment);
    
    RelativePath model = ModuleSystemCommands.importModel(modulePath, environment);
    ModuleSystemCommands.registerSearchedModelFiles(modulePath, driverResult, environment);
    if (model != null && transformationPaths == null) {
      success = true;
    }
    else if (model != null) {
      
      IStrategoTerm term = ATermCommands.atermFromFile(model.getAbsolutePath());
      List<RelativePath> resolvedTransformationPaths = resolveTransformationPaths(modulePath, transformationPaths, importTerm);
      
      List<String> resolvedTransformationStrings = new LinkedList<String>();
      for (RelativePath p : resolvedTransformationPaths)
        resolvedTransformationStrings.add(FileCommands.dropExtension(p.getRelativePath()).replace('/', '_'));
      String resolvedTransformationString = StringCommands.printListSeparated(resolvedTransformationStrings, "$");
      
      IStrategoTerm transformedTerm = term;
      Path transformedTermPath = environment.new RelativePathBin(FileCommands.dropExtension(model.getRelativePath()) + "$" + resolvedTransformationString + ".aterm");
      
      boolean transformSuccessful = false;
      try {
        log.beginTask("", "Transform model " + model.getRelativePath() + " with " + StringCommands.printModuleList(resolvedTransformationPaths, ", "));
        for (RelativePath strPath : resolvedTransformationPaths)
          transformedTerm = executeTransformation(strPath, importTerm, transformedTerm);
        ATermCommands.atermToFile(transformedTerm, transformedTermPath);
        transformSuccessful = true;
      } finally {
        log.endTask(transformSuccessful, "Transformed model to " + transformedTermPath, "Transform model failed");
      }
      
      Environment modelEnvironment = compileImportedModel(model, modulePath, importedResult, importedResultPath, transformedTerm, importTerm);
      Path modelResultPath = modelEnvironment.new RelativePathBin(modulePath + ".dep");
      success = processImport(modulePath, Result.readDependencyFile(modelResultPath, modelEnvironment), modelResultPath, null, importTerm, true);
    }

    return success;
  }


  private Environment compileImportedModel(RelativePath model, String modulePath, Result importedResult, 
      Path importedResultPath, IStrategoTerm transformedTerm, IStrategoTerm importTerm) throws IOException {
    /*
     * A new temp directory is created to work as the current model
     * import's bin directory. If the imported module already has a model bin
     * it is taken instead.
     */
    Path modelBinPath;
    if (importedResult != null && importedResult.getModelBinPath() != null)
      modelBinPath = importedResult.getModelBinPath();
    else {
      modelBinPath = FileCommands.newTempDir();
      importedResult.setModelBinPath(modelBinPath);
      importedResult.writeDependencyFile(importedResultPath);
    }

    /*
     * modelEnvironment is the environment for compiling imported modules.
     * The output is stored in a temporary bin which has been created before.
     */
    Environment modelEnvironment = new Environment();
    modelEnvironment.setAtomicImportParsing(environment.isAtomicImportParsing());
    modelEnvironment.setGenerateJavaFile(environment.isGenerateJavaFile());
    modelEnvironment.setSourcePath(environment.getSourcePath());
    modelEnvironment.setRoot(environment.getRoot());
    modelEnvironment.setBin(modelBinPath);
    modelEnvironment.getIncludePath().add(environment.getBin());
    
    Path modelResultPath = modelEnvironment.new RelativePathBin(modulePath + ".dep");
    
    boolean recompile = !FileCommands.exists(modelResultPath) || !Result.readDependencyFile(modelResultPath, modelEnvironment).isUpToDate(model, modelEnvironment);
    if (recompile) {
      try {
        log.log("Need to compile the imported model first; processing it now.");
        Result modelResult = compile(transformedTerm, new RelativeSourceLocationPath(new SourceLocation(model.getBasePath(), modelEnvironment), model.getRelativePath()), monitor);
        
        driverResult.addDependency(modelResultPath, modelEnvironment);
        environment.getIncludePath().add(modelBinPath);
        environment.getIncludePath().addAll(modelResult.getModelBinPaths());
      } catch (Exception e) {
        setErrorMessage(importTerm, "compilation of imported module failed: " + e.getLocalizedMessage());
      } finally {
        log.log("CONTINUE PROCESSING'" + sourceFile + "'.");
      }
    }
    
    /*
     * returns the Environment used for compiling the import
     */
    return modelEnvironment;
  }


  private IStrategoTerm executeTransformation(RelativePath strPath, IStrategoTerm importTerm, IStrategoTerm currentTerm) throws IOException {
    /*
     * create a temporary stratego file that connects already available imports
     * with the currently processed transformation
     */
    Path compoundStr = FileCommands.newTempFile("str");
    StringBuilder builder = new StringBuilder();
    builder.append("module ").append(FileCommands.fileName(compoundStr)).append("\n");
    builder.append("imports ");
    builder.append(StringCommands.printModuleList(availableSTRImports, " "));
    builder.append(" ").append(FileCommands.dropExtension(strPath.getRelativePath()));
    FileCommands.writeToFile(compoundStr, builder.toString());
    
    Path trans = null;
    try {
      log.beginTask("Compile transformation", "Compile transformation " + strPath.getRelativePath());
      trans = STRCommands.compile(compoundStr, "main-" + FileCommands.fileName(strPath), driverResult.getFileDependencies(environment), strParser, strjContext, strCache, environment);
    } catch (TokenExpectedException e) {
    } catch (BadTokenException e) {
    } catch (InvalidParseTableException e) {
    } catch (SGLRException e) {
    } finally {
      log.endTask();
    }
    
    if (trans == null)
      throw new StrategoException("compilation of transformation " + FileCommands.fileName(strPath) + " failed");
      
    /*  
     * applies each transformation's "main-<transformation name>" rule on the AST of
     * the current import
     */
    try {
      IStrategoTerm newTransformedTerm = STRCommands.assimilate("main-" + FileCommands.fileName(strPath), trans, currentTerm, interp);
      
      if (newTransformedTerm == null)
        throw new StrategoException("transformation failed: " + currentTerm);
      
      return newTransformedTerm;
    } catch (Exception e) {
      throw new RuntimeException("model transformation failed; " + strPath.getRelativePath() + " applied to " + currentTerm, e);
    }
  }


  private List<RelativePath> resolveTransformationPaths(String modulePath,
      List<String> transformationPaths, 
      IStrategoTerm importTerm) {
    log.beginTask("Resolving transformation paths for '"+modulePath+"'.");
    List<RelativePath> resolvedTransformationPaths = new ArrayList<RelativePath>();
    for (String transformationPath : transformationPaths) {
      if (!transformationPath.contains("/")) {  // this branch searches for relative imports
        
        boolean moduleFound = false;
        for (RelativePath importPath : availableSTRImports) {
          if (FileCommands.dropExtension(importPath.getAbsolutePath()).endsWith(transformationPath)) {
            // QST: Adding only transformationPath correct?
            resolvedTransformationPaths.add(importPath);
            moduleFound = true;
            break;
          }
        }
        if (!moduleFound) {
          log.logErr("module '"+ transformationPath +"' not found in available imports");
          // QST: Which Path should be added? (to produce an error) Which absolute path should it be?
          resolvedTransformationPaths.add(environment.new RelativePathBin(transformationPath+".str"));
        }
      } else { // this branch searches for FQN imports
        // QST: Use of searchFile correct here?
        RelativePath p = ModuleSystemCommands.searchFile(transformationPath, ".str", environment);
        if (p==null)
          ATermCommands.setErrorMessage(importTerm, "cannot resolve module '"+ transformationPath.replace("/", ".") + "'");
        else
          resolvedTransformationPaths.add(p);
      }
    }
    log.endTask();
    return resolvedTransformationPaths;
  }

  private void processJavaTypeDec(IStrategoTerm toplevelDecl) throws IOException {
    log.beginTask(
        "processing",
        "PROCESS the desugared Java type declaration.");
    try {
      
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);

      
      log.beginTask("Generate Java code.");
      try {
        IStrategoTerm dec =  isApplication(toplevelDecl, "JavaTypeDec") ? getApplicationSubterm(toplevelDecl, "JavaTypeDec", 0) : toplevelDecl;
        
        String decName = Term.asJavaString(dec.getSubterm(0).getSubterm(1).getSubterm(0));
        
        Path clazz = environment.new RelativePathBin(relPackageNameSep() + decName + ".class");
        
        generatedJavaClasses.add(clazz);
        driverResult.appendToFile(javaOutFile, SDFCommands.prettyPrintJava(dec, interp) + "\n");
      } finally {
        log.endTask();
      }
    } finally {
      log.endTask();
    }
  }

  private void processSugarDec(IStrategoTerm toplevelDecl) throws IOException,
      InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    log.beginTask(
        "processing",
        "PROCESS the desugared sugar declaration.");
    try {
      if (!sugaredBodyDecls.contains(lastSugaredToplevelDecl))
        sugaredBodyDecls.add(lastSugaredToplevelDecl);

      
      boolean isNative;
      String extName = null;
      String fullExtName = null;
      boolean isPublic = false;

      IStrategoTerm head = getApplicationSubterm(toplevelDecl, "SugarDec", 0);
      IStrategoTerm body= getApplicationSubterm(toplevelDecl, "SugarDec", 1);
      
      log.beginTask("Extracting name and accessibility of the sugar.");
      try {
        isNative = isApplication(head, "NativeSugarDecHead");
        
        if (isNative) {
          extName =
            SDFCommands.prettyPrintJava(
            getApplicationSubterm(head, "NativeSugarDecHead", 2), interp);
          
          IStrategoTerm mods = getApplicationSubterm(head, "NativeSugarDecHead", 0);
          
          for (IStrategoTerm t : getList(mods))
            if (isApplication(t, "Public"))
            {
              isPublic = true;
              break;
            }
        }
        else {
          extName =
            SDFCommands.prettyPrintJava(
            getApplicationSubterm(head, "SugarDecHead", 1), interp);    
          
          IStrategoTerm mods = getApplicationSubterm(head, "SugarDecHead", 0);
          
          for (IStrategoTerm t : getList(mods))
            if (isApplication(t, "Public"))
            {
              isPublic = true;
              break;
            }
        }
        
        
        
        fullExtName = relPackageNameSep() + extName;

        log.log("The name of the sugar is '" + extName + "'.");
        log.log("The full name of the sugar is '" + fullExtName + "'.");

        if (isPublic)
          log.log("The sugar is public.");
        else
          log.log("The sugar is not public.");
        
        if (isNative)
          log.log("The sugar is native.");
        else
          log.log("The sugar is not native.");


      } finally {
        log.endTask();
      }
      
      RelativePath sdfExtension = environment.new RelativePathBin(relPackageNameSep() + extName + ".sdf");
      RelativePath strExtension = environment.new RelativePathBin(relPackageNameSep() + extName + ".str");
      
      String sdfImports = " imports " + StringCommands.printModuleList(availableSDFImports, " ") + "\n";
      String strImports = " imports " + StringCommands.printModuleList(availableSTRImports, " ") + "\n";
      
      if (isNative) {
        String nativeModule = getString(getApplicationSubterm(body, "NativeSugarBody", 0)); 
        
        if (nativeModule.length() > 1)
            // remove quotes
          nativeModule = nativeModule.substring(1, nativeModule.length() - 1);
          
        RelativePath nativeSdf = ModuleSystemCommands.searchFile(nativeModule, ".sdf", environment);
        if (FileCommands.exists(nativeSdf)) {
          availableSDFImports.add(nativeSdf);
          driverResult.generateFile(
              sdfExtension, 
              "module " + fullExtName + "\n" 
              + sdfImports 
              + "imports " + nativeModule);
        }

        RelativePath nativeStr = ModuleSystemCommands.searchFile(nativeModule, ".str", environment);
        if (FileCommands.exists(nativeStr)) {
          availableSTRImports.add(nativeStr);
          driverResult.generateFile(
              strExtension, 
              "module " + fullExtName + "\n" 
              + strImports
              + "imports " + nativeModule);
        }
      }
      else {
        // this is a list of SDF and Stratego statements
        IStrategoTerm sugarBody = getApplicationSubterm(body, "SugarBody", 0);
  
        IStrategoTerm sdfExtract = fixSDF(extractSDF(sugarBody, extractionContext), interp);
        IStrategoTerm strExtract = extractSTR(sugarBody, extractionContext);
        
        
        String sdfExtensionHead =
          "module " + fullExtName + "\n" 
          + sdfImports
          + (isPublic ? "exports " : "hiddens ") + "\n"
          + "  (/)" + "\n";

        String sdfExtensionContent = SDFCommands.prettyPrintSDF(sdfExtract, interp);

        String sdfSource = SDFCommands.makePermissiveSdf(sdfExtensionHead + sdfExtensionContent, makePermissiveContext);
        driverResult.generateFile(sdfExtension, sdfSource);
        availableSDFImports.add(sdfExtension);
        
        if (CommandExecution.FULL_COMMAND_LINE && generateFiles)
          log.log("Wrote SDF file to '" + sdfExtension.getAbsolutePath() + "'.");
        
        String strExtensionTerm = 
            "Module(" + "\"" + fullExtName+ "\"" + ", " 
                      + strExtract + ")" + "\n";

        String strExtensionContent = SDFCommands.prettyPrintSTR(ATermCommands.atermFromString(strExtensionTerm), interp);
        
        int index = strExtensionContent.indexOf('\n');
        if (index >= 0)
          strExtensionContent =
            strExtensionContent.substring(0, index + 1) + "\n"
            + strImports + "\n"
            + strExtensionContent.substring(index + 1);
        else
          strExtensionContent += strImports;
          
        
        driverResult.generateFile(strExtension, strExtensionContent);
        availableSTRImports.add(strExtension);
        
        if (CommandExecution.FULL_COMMAND_LINE && generateFiles)
          log.log("Wrote Stratego file to '" + strExtension.getAbsolutePath() + "'.");
      }
      
      /*
       * adapt current grammar
       */
      if (FileCommands.exists(sdfExtension)) {
        buildCompoundSdfModule();
      }

      /*
       * adapt current transformation
       */
      if (FileCommands.exists(strExtension))
        buildCompoundStrModule();

    } finally {
      log.endTask();
    }
  }
  
  private void buildCompoundSdfModule() throws IOException {
    FileCommands.deleteTempFiles(currentGrammarSDF);
    currentGrammarSDF = FileCommands.newTempFile("sdf");
    currentGrammarModule = FileCommands.fileName(currentGrammarSDF);
    StringBuilder builder = new StringBuilder();
    builder.append("module ").append(currentGrammarModule).append("\n");
    builder.append("imports ");
    builder.append(StringCommands.printModuleList(availableSDFImports, " "));
    
    FileCommands.writeToFile(currentGrammarSDF, builder.toString());
  }
  
  private void buildCompoundStrModule() throws IOException {
    FileCommands.deleteTempFiles(currentTransSTR);
    currentTransSTR = FileCommands.newTempFile("str");
    currentTransModule = FileCommands.fileName(currentTransSTR);
    StringBuilder builder = new StringBuilder();
    builder.append("module ").append(currentTransModule).append("\n");
    builder.append("imports ");
    builder.append(StringCommands.printModuleList(availableSTRImports, " "));
    
    FileCommands.writeToFile(currentTransSTR, builder.toString());
  }

  private void checkCurrentGrammar() throws IOException, InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
    log.beginTask("checking grammar", "CHECK current grammar");
    
    try {
      Path p = SDFCommands.compile(currentGrammarSDF, currentGrammarModule, driverResult.getFileDependencies(environment), sdfParser, sdfContext, makePermissiveContext, sdfCache, environment);
      FileCommands.deleteTempFiles(p);
    } finally {
      log.endTask();
    }
  }
  
  private void checkCurrentTransformation() throws TokenExpectedException, BadTokenException, IOException, InvalidParseTableException, SGLRException {
    log.beginTask("checking transformation", "CHECK current transformation");
    
    try {
      FileCommands.deleteTempFiles(currentTransProg);
      currentTransProg = STRCommands.compile(currentTransSTR, "main", driverResult.getFileDependencies(environment), strParser, strjContext, strCache, environment);
    } catch (StrategoException e) {
      String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
      log.logErr(msg);
      setErrorMessage(lastSugaredToplevelDecl, msg);
    } finally {
      log.endTask();
    }
  }
  
  private void initEditorServices() throws IOException, TokenExpectedException, BadTokenException, SGLRException {
    IStrategoTerm initEditor = editorServicesParser.parse(new FileInputStream(StdLib.initEditor.getPath()), StdLib.initEditor.getPath());
    
    IStrategoTerm services = ATermCommands.getApplicationSubterm(initEditor, "Module", 2);
    
    if (!ATermCommands.isList(services))
      throw new IllegalStateException("initial editor ill-formed");
    
    for (IStrategoTerm service : ATermCommands.getList(services))
      driverResult.addEditorService(service);
  }
  
  private void init(ToplevelDeclarationProvider declProvider, RelativePath sourceFile, boolean generateFiles) throws FileNotFoundException, IOException, InvalidParseTableException {
    javaOutFile = null;
    depOutFile = null;
    // FileCommands.createFile(tmpOutdir, relModulePath + ".java");

    this.declProvider = declProvider;
    this.sourceFile = new RelativeSourceLocationPath(new SourceLocation(sourceFile.getBasePath(), environment), sourceFile);
    this.generateFiles = generateFiles;
    
    this.driverResult = new Result(generateFiles);

    currentGrammarSDF = new AbsolutePath(StdLib.initGrammar.getPath());
    currentGrammarModule = StdLib.initGrammarModule;

    currentTransSTR = new AbsolutePath(StdLib.initTrans.getPath());
    currentTransModule = StdLib.initTransModule;

    // list of imports that contain SDF extensions
    availableSDFImports = new ArrayList<RelativePath>();
    availableSDFImports.add(new RelativePath(new AbsolutePath(StdLib.stdLibDir.getPath()), StdLib.initGrammarModule + ".sdf"));

    // list of imports that contain Stratego extensions
    availableSTRImports = new ArrayList<RelativePath>();
    availableSTRImports.add(new RelativePath(new AbsolutePath(StdLib.stdLibDir.getPath()), StdLib.initTransModule + ".str"));

    inputTreeBuilder = new RetractableTreeBuilder();
    
    sdfParser = new JSGLRI(org.strategoxt.imp.runtime.Environment.loadParseTable(StdLib.sdfTbl.getPath()), "Sdf2Module");
    strParser = new JSGLRI(org.strategoxt.imp.runtime.Environment.loadParseTable(StdLib.strategoTbl.getPath()), "StrategoModule");
    editorServicesParser = new JSGLRI(org.strategoxt.imp.runtime.Environment.loadParseTable(StdLib.editorServicesTbl.getPath()), "Module");

    interp = new HybridInterpreter(); //TODO (ATermCommands.factory);
    sdfContext = tools.init();
    makePermissiveContext = make_permissive.init();
    extractionContext = extraction.init();
    strjContext = org.strategoxt.strj.strj.init();
  }
  
  /**
   * @param args
   * @throws IOException
   */
  public static void main(String[] args) {
    // log.log("This is the extensible java compiler.");
    Environment environment = new Environment();
    
    try {
      String[] sources = DriverCLI.handleOptions(args, environment);

      if (environment.getSourcePath().isEmpty())
        environment.getSourcePath().add(new SourceLocation(new AbsolutePath("."), environment));
      
      for (String source : sources)
      {
        RelativeSourceLocationPath p = ModuleSystemCommands.locateSourceFile(FileCommands.dropExtension(source), environment.getSourcePath());
        
        allInputFiles.add(p);
        pendingInputFiles.add(p);
      }
      
      IProgressMonitor monitor = new PrintProgressMonitor(System.out);
      
      for (final RelativeSourceLocationPath sourceFile : allInputFiles) {
        monitor.beginTask("compile " + sourceFile, IProgressMonitor.UNKNOWN);
        Result res = compile(sourceFile, monitor);
        if (!DriverCLI.processResultCLI(res, sourceFile, new File(".").getAbsolutePath()))
          throw new RuntimeException("compilation of " + sourceFile + " failed");
      }
      
    } catch (Exception e) {
      e.printStackTrace();
    } catch (CLIError e) {
      Log.log.log(e.getMessage());
      Log.log.log("");
      e.showUsage();
    }

    // kills all remaining subprocesses, if any
    // log.log("The extensible java compiler has done its job and says 'good bye'.");
    System.exit(0);
  }
  
  
  @SuppressWarnings("unchecked")
  private void initializeCaches(Environment environment, boolean force) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path cacheVersion = environment.new RelativePathCache("version");
    
    if (!cacheVersion.getFile().exists() ||
        !FileCommands.readFileAsString(cacheVersion).equals(CACHE_VERSION)) {

      for (File f : environment.getCacheDir().getFile().listFiles())
        f.delete();
      
      FileCommands.writeToFile(cacheVersion, CACHE_VERSION);
    }
    
    Path sdfCachePath = environment.new RelativePathCache("sdfCache");
    Path strCachePath = environment.new RelativePathCache("strCache");
    
    if (sdfCache == null || force)
      try {
        // log.log("load sdf cache from " + sdfCachePath);
          sdfCache = reallocate(
              (ModuleKeyCache<Path>) new ObjectInputStream(new FileInputStream(sdfCachePath.getFile())).readObject(),
              environment);
      } 
      catch (Exception e) {
        log.logErr("Could read sdf cache, generating new one.");
        sdfCache = new ModuleKeyCache<Path>();
        for (File f : environment.getCacheDir().getFile().listFiles())
          if (f.getPath().endsWith(".tbl"))
            f.delete();
      }
    else if (sdfCache == null)
      sdfCache = new ModuleKeyCache<Path>();
    
    if (strCache == null || force)
      try {
        // log.log("load str cache from " + strCachePath);
        strCache = reallocate(
            (ModuleKeyCache<Path>) new ObjectInputStream(new FileInputStream(strCachePath.getFile())).readObject(),
            environment);
      } 
      catch (Exception e) {
        log.logErr("Could read str cache, generating new one.");
        strCache = new ModuleKeyCache<Path>();
        for (File f : environment.getCacheDir().getFile().listFiles())
          if (f.getPath().endsWith(".jar"))
            f.delete();
      }
    else if (strCache == null)
      strCache = new ModuleKeyCache<Path>();
  }

  
  private static ModuleKeyCache<Path> reallocate(ModuleKeyCache<Path> cache, Environment env) {
    ModuleKeyCache<Path> res = new ModuleKeyCache<Path>();
    
    for (Entry<ModuleKey, Path> e : cache.entrySet()) {
      Map<Path, Integer> imports = new HashMap<Path, Integer>();
      for (Entry<Path, Integer> e2 : e.getKey().imports.entrySet())
        imports.put(Path.reallocate(e2.getKey(), env), e2.getValue());
      
      res.put(new ModuleKey(imports, e.getKey().body), Path.reallocate(e.getValue(), env));
    }
    
    return res;
  }


  private void storeCaches(Environment environment) throws IOException {
    if (environment.getCacheDir() == null)
      return;
    
    Path cacheVersion = environment.new RelativePathCache("version");
    FileCommands.writeToFile(cacheVersion, CACHE_VERSION);
    
    Path sdfCachePath = environment.new RelativePathCache("sdfCache");
    Path strCachePath = environment.new RelativePathCache("strCache");

    if (!sdfCachePath.getFile().exists())
      FileCommands.createFile(sdfCachePath);

    if (!strCachePath.getFile().exists())
      FileCommands.createFile(strCachePath);
    
    if (sdfCache != null) {
//      log.log("store sdf cache in " + sdfCachePath);
//      log.log("sdf cache size: " + sdfCache.size());
      FileCommands.createFile(sdfCachePath);
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(sdfCachePath.getFile()));
      try {
        oos.writeObject(sdfCache);
      } finally {
        oos.close();
      }
    }
    
    if (strCache != null) {
//      log.log("store str cache in " + strCachePath);
//      log.log("str cache size: " + strCache.size());
      ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(strCachePath.getFile()));
      try {
        oos.writeObject(strCache);
      } finally {
        oos.close();
      }
    }
  }

  private String relPackageNameSep() {
    if (relPackageName == null || relPackageName.isEmpty())
      return "";
    
    return relPackageName + sep;
  }
  
  /**
   * @return the non-desugared syntax tree of the complete file.
   */
  private IStrategoTerm makeSugaredSyntaxTree() {
    
    // XXX empty lists => no tokens
    IStrategoTerm packageDecl = ATermCommands.makeSome(sugaredPackageDecl, inputTreeBuilder.getTokenizer() == null ? null : inputTreeBuilder.getTokenizer().getTokenAt(0));
    IStrategoTerm imports = 
      ATermCommands.makeList("JavaImportDec*", ImploderAttachment.getRightToken(packageDecl), sugaredImportDecls);
    IStrategoTerm body =
      ATermCommands.makeList("TypeOrSugarDec*", ImploderAttachment.getRightToken(imports), sugaredBodyDecls);
    
    IStrategoTerm term =
      ATermCommands.makeAppl("SugarCompilationUnit", "SugarCompilationUnit", 3,
        packageDecl,
        imports,
        body);
    
    if (ImploderAttachment.getTokenizer(term) != null) {
      ImploderAttachment.getTokenizer(term).setAst(term);
      ImploderAttachment.getTokenizer(term).initAstNodeBinding();
    }
    
    return term;
  }
  
  /**
   * @return the desugared syntax tree of the complete file.
   */
  private IStrategoTerm makeDesugaredSyntaxTree() {
    IStrategoTerm packageDecl = ATermCommands.makeSome(desugaredPackageDecl, inputTreeBuilder.getTokenizer() == null ? null : inputTreeBuilder.getTokenizer().getTokenAt(0));
    IStrategoTerm imports = 
      ATermCommands.makeList("JavaImportDec*", ImploderAttachment.getRightToken(packageDecl), desugaredImportDecls);
    IStrategoTerm body =
      ATermCommands.makeList("TypeOrSugarDec*", ImploderAttachment.getRightToken(imports), desugaredBodyDecls);
    
    IStrategoTerm term =
      ATermCommands.makeAppl("SugarCompilationUnit", "SugarCompilationUnit", 3,
        packageDecl,
        imports,
        body);
    
    return term;
  }
  
  public synchronized void interrupt() {
    this.interrupt = true;
  }
  
  private synchronized void stopIfInterrupted() throws InterruptedException {
    if (interrupt || monitor.isCanceled()) {
      monitor.setCanceled(true);
      log.log("interrupted " + sourceFile);
      throw new InterruptedException();
    }
  }

  private void stepped() throws InterruptedException {
    stopIfInterrupted();
    monitor.worked(1);
  }
  
  private void clearGeneratedStuff() throws IOException {
    if (generateFiles && driverResult.getGenerationLog() != null && FileCommands.exists(driverResult.getGenerationLog())) {

      ObjectInputStream ois = null;
      
      try {
        ois = new ObjectInputStream(new FileInputStream(driverResult.getGenerationLog().getFile()));
        while (true) {
          try {
            Path p = Path.readPath(ois, environment);
            FileCommands.delete(p);
          } catch (ClassNotFoundException e) { 
          }
        }
      } catch (EOFException e) {
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        if (ois != null)
          ois.close();
        FileCommands.delete(driverResult.getGenerationLog());
      }
    }
  }
  
  private void setErrorMessage(IStrategoTerm toplevelDecl, String msg) {
    driverResult.logError(msg);
    ATermCommands.setErrorMessage(toplevelDecl, msg);
  }
  
  
  class SourceCodeToplevelDeclarationProvider implements ToplevelDeclarationProvider {

    private String lastRemainingInput;
    private String remainingInput;
    private final int hash;
    private JSGLRI parser;

    public SourceCodeToplevelDeclarationProvider(String source) {
      this.remainingInput = source;
      hash = source.hashCode();
      // XXX need to load ANY parse table, preferably an empty one.
      try {
        parser = new JSGLRI(org.strategoxt.imp.runtime.Environment.loadParseTable(StdLib.sdfTbl.getPath()), "Sdf2Module");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }
    
    @Override
    public IStrategoTerm getNextToplevelDecl(boolean recovery) throws IOException, ParseException, InvalidParseTableException, SGLRException {
      IncrementalParseResult parseResult =
        parseNextToplevelDeclaration(remainingInput, recovery);
      lastRemainingInput = remainingInput;
      remainingInput = parseResult.getRest();
      return parseResult.getToplevelDecl();
    }

    private IncrementalParseResult parseNextToplevelDeclaration(String input, boolean recovery) throws IOException, ParseException, InvalidParseTableException, SGLRException {
      int start = inputTreeBuilder.getTokenizer() == null ? 0 : inputTreeBuilder.getTokenizer().getStartOffset();
      log.beginTask("parsing", "PARSE the next toplevel declaration.");
      try {
        IStrategoTerm remainingInputTerm = null;
        
        try {
          remainingInputTerm = currentParse(input, recovery);
        } catch (Exception e) {
          if (recovery) {
            e.printStackTrace();
            remainingInputTerm = currentParse(input, false);
          }
          
          if (remainingInputTerm == null)
            throw e;
        }
    
        if (remainingInputTerm == null)
          throw new ParseException("could not parse toplevel declaration in:\n"
              + input, -1);
    
        if (!isApplication(remainingInputTerm, "NextToplevelDeclaration"))
          throw new ATermCommands.MatchError(remainingInputTerm, "NextToplevelDeclaration");
        
        IStrategoTerm toplevelDecl = getApplicationSubterm(remainingInputTerm, "NextToplevelDeclaration", 0);
        IStrategoTerm restTerm = getApplicationSubterm(remainingInputTerm, "NextToplevelDeclaration", 1);
        String rest = getString(restTerm);
    
        if (input.equals(rest))
          throw new SGLRException(parser.getParser(), "empty toplevel declaration parse rule");
        
        try {
          if (!rest.isEmpty())
            inputTreeBuilder.retract(restTerm);
        } catch (Throwable t) {
          t.printStackTrace();
        }
        
        if (toplevelDecl == null || rest == null)
          throw new ParseException(
              "could not parse next toplevel declaration in:\n"
                  + remainingInputTerm.toString(),
              -1);
        
        Path tmpFile = FileCommands.newTempFile("aterm");
        FileCommands.writeToFile(tmpFile, toplevelDecl.toString());
        log.log("next toplevel declaration parsed: " + tmpFile);
    
        return new IncrementalParseResult(toplevelDecl, rest);
      } catch (Exception e) {
        if (!recovery)
          throw new SGLRException(parser.getParser(), "parsing failed", e);
        
        String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
        
        if (!(e instanceof StrategoException) && !(e instanceof SGLRException))
          e.printStackTrace();
        else
          log.logErr(msg);
        
        if (inputTreeBuilder.getTokenizer().getStartOffset() > start) {
          IToken token = inputTreeBuilder.getTokenizer().getTokenAtOffset(start);
          ((RetractableTokenizer) inputTreeBuilder.getTokenizer()).retractTo(token.getIndex());
          inputTreeBuilder.setOffset(start);
        }
        
        IToken right = inputTreeBuilder.getTokenizer().makeToken(start + input.length() - 1, IToken.TK_STRING, true);
        IToken left = inputTreeBuilder.getTokenizer().getTokenAtOffset(start);
        inputTreeBuilder.getTokenizer().makeToken(inputTreeBuilder.getTokenizer().getStartOffset() - 1, IToken.TK_EOF, true);
        IStrategoTerm term = ATermCommands.factory.makeString(input);
        ImploderAttachment.putImploderAttachment(term, false, "String", left, right);
        setErrorMessage(term, msg);
        return new IncrementalParseResult(term, "");
      } finally {
        log.endTask();
      }
    }

    @Override
    public void retract(IStrategoTerm term) {
      if (lastRemainingInput == null)
        throw new IllegalStateException("cannot retract now");
        
      if (term != null) {
        remainingInput = lastRemainingInput;
        lastRemainingInput = null;
        inputTreeBuilder.retract(term);
      }
    }

    @Override
    public IStrategoTerm desugarToplevelDecl(IStrategoTerm term) throws TokenExpectedException, BadTokenException, IOException, InvalidParseTableException, SGLRException {
      return currentDesugar(term);
    }


    private IStrategoTerm currentParse(String remainingInput, boolean recovery) throws IOException,
        InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
      // recompile the current grammar definition
      currentGrammarTBL = SDFCommands.compile(currentGrammarSDF, currentGrammarModule, driverResult.getFileDependencies(environment), sdfParser, sdfContext, makePermissiveContext, sdfCache, environment);
    //  FileCommands.deleteTempFiles(driverResult.getLastParseTable());
    //  driverResult.setLastParseTable(currentGrammarTBL);
      ParseTable table = org.strategoxt.imp.runtime.Environment.loadParseTable(currentGrammarTBL.getAbsolutePath());
      
      IStrategoTerm parseResult = null;
    
      parser.setUseRecovery(recovery);
      
      // read next toplevel decl and stop if that fails
      try {
        parseResult = SDFCommands.parseImplode(
            table,
            remainingInput,
            "NextToplevelDeclaration",
            false,
            inputTreeBuilder,
            parser);
      } finally {
        if (recovery) {
          for (BadTokenException e : parser.getCollectedErrors())
            driverResult.logParseError(e);
        }
      }
      
      return parseResult;
    }

    private IStrategoTerm currentDesugar(IStrategoTerm term) throws IOException,
        InvalidParseTableException, TokenExpectedException, BadTokenException, SGLRException {
      // assimilate toplevelDec using current transformation
    
      log.beginTask(
          "desugaring",
          "DESUGAR the current toplevel declaration.");
      try {
        FileCommands.deleteTempFiles(currentTransProg);
        currentTransProg = STRCommands.compile(currentTransSTR, "main", driverResult.getFileDependencies(environment), strParser, strjContext, strCache, environment);
    
        return STRCommands.assimilate(currentTransProg, term, interp);
      } catch (StrategoException e) {
        String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
        
        log.logErr(msg);
    
        setErrorMessage(term, msg);
        return term;
      } finally {
        log.endTask();
      }
    }

    @Override
    public boolean hasNextToplevelDecl() {
      return !remainingInput.isEmpty();
    }

    @Override
    public int getSourceHashCode() {
      return hash;
    }
    
  }
  
  
  class TermToplevelDeclarationProvider implements ToplevelDeclarationProvider {

    private List<IStrategoTerm> terms;
    int index;
    
    public TermToplevelDeclarationProvider(IStrategoTerm source) {
      IStrategoTerm packageDecOption = ATermCommands.getApplicationSubterm(source, "SugarCompilationUnit", 0);
      IStrategoTerm importDecs = ATermCommands.getApplicationSubterm(source, "SugarCompilationUnit", 1);
      IStrategoTerm bodyDecs = ATermCommands.getApplicationSubterm(source, "SugarCompilationUnit", 2);
      
      index = 0;
      terms = new ArrayList<IStrategoTerm>();
      
      if (ATermCommands.isApplication(packageDecOption, "Some"))
        terms.add(ATermCommands.getApplicationSubterm(packageDecOption, "Some", 0));
      terms.addAll(ATermCommands.getList(importDecs));
      terms.addAll(ATermCommands.getList(bodyDecs));
    }
    
    @Override
    public IStrategoTerm getNextToplevelDecl(boolean recovery) {
      return terms.get(index++);
    }

    @Override
    public IStrategoTerm desugarToplevelDecl(IStrategoTerm term) {
      return term;
    }

    @Override
    public boolean hasNextToplevelDecl() {
      return index < terms.size();
    }

    @Override
    public int getSourceHashCode() {
      return terms.hashCode();
    }

    @Override
    public void retract(IStrategoTerm term) {
      if (index <= 0)
        throw new IllegalStateException();
      
      if (terms.get(index - 1).equals(term))
        index--;
      else
        throw new IllegalArgumentException();
    }
    
  }
  
  private void handleException(Exception e, IStrategoTerm trm) {
    String msg = e.getClass().getName() + " " + e.getLocalizedMessage() != null ? e.getLocalizedMessage() : e.toString();
    
    if (!(e instanceof StrategoException))
      e.printStackTrace();
    else
      log.logErr(msg);

    setErrorMessage(trm, msg);
  }
}
