package org.sugarj.driver.transformations.extraction;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class init_for_analysis_0_0 extends Strategy 
{ 
  public static init_for_analysis_0_0 instance = new init_for_analysis_0_0();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term)
  { 
    context.push("init_for_analysis_0_0");
    Fail17:
    { 
      term = bottomup_1_0.instance.invoke(context, term, lifted3.instance);
      if(term == null)
        break Fail17;
      context.popOnSuccess();
      if(true)
        return term;
    }
    context.popOnFailure();
    return null;
  }
}