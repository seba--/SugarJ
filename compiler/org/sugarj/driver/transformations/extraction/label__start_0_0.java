package org.sugarj.driver.transformations.extraction;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class label__start_0_0 extends Strategy 
{ 
  public static label__start_0_0 instance = new label__start_0_0();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term)
  { 
    Fail1013:
    { 
      if(term.getTermType() != IStrategoTerm.APPL || ext._conslabel_start_0 != ((IStrategoAppl)term).getConstructor())
        break Fail1013;
      if(true)
        return term;
    }
    context.push("label__start_0_0");
    context.popOnFailure();
    return null;
  }
}