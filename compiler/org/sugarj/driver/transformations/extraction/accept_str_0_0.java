package org.sugarj.driver.transformations.extraction;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class accept_str_0_0 extends Strategy 
{ 
  public static accept_str_0_0 instance = new accept_str_0_0();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term)
  { 
    Fail704:
    { 
      IStrategoTerm k_332 = null;
      if(term.getTermType() != IStrategoTerm.APPL || ext._constransformation_elem_1 != ((IStrategoAppl)term).getConstructor())
        break Fail704;
      k_332 = term.getSubterm(0);
      term = k_332;
      if(true)
        return term;
    }
    context.push("accept_str_0_0");
    context.popOnFailure();
    return null;
  }
}