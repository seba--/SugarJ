package org.sugarj.driver.transformations.extraction;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") final class lifted26 extends Strategy 
{ 
  public static final lifted26 instance = new lifted26();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term)
  { 
    Fail2190:
    { 
      term = extraction.const49;
      if(true)
        return term;
    }
    return null;
  }
}