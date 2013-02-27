package org.sugarj.driver.transformations.renaming;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class $List_1_0 extends Strategy 
{ 
  public static $List_1_0 instance = new $List_1_0();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term, Strategy d_18)
  { 
    ITermFactory termFactory = context.getFactory();
    context.push("List_1_0");
    Fail137:
    { 
      IStrategoTerm k_113 = null;
      IStrategoTerm j_113 = null;
      if(term.getTermType() != IStrategoTerm.APPL || out._consList_1 != ((IStrategoAppl)term).getConstructor())
        break Fail137;
      j_113 = term.getSubterm(0);
      IStrategoList annos123 = term.getAnnotations();
      k_113 = annos123;
      term = d_18.invoke(context, j_113);
      if(term == null)
        break Fail137;
      term = termFactory.annotateTerm(termFactory.makeAppl(out._consList_1, new IStrategoTerm[]{term}), checkListAnnos(termFactory, k_113));
      context.popOnSuccess();
      if(true)
        return term;
    }
    context.popOnFailure();
    return null;
  }
}