package org.sugarj.driver.transformations.extraction;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class $Default$Var$Dec_1_0 extends Strategy 
{ 
  public static $Default$Var$Dec_1_0 instance = new $Default$Var$Dec_1_0();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term, Strategy t_342)
  { 
    ITermFactory termFactory = context.getFactory();
    context.push("DefaultVarDec_1_0");
    Fail892:
    { 
      IStrategoTerm z_446 = null;
      IStrategoTerm y_446 = null;
      if(term.getTermType() != IStrategoTerm.APPL || ext._consDefaultVarDec_1 != ((IStrategoAppl)term).getConstructor())
        break Fail892;
      y_446 = term.getSubterm(0);
      IStrategoList annos138 = term.getAnnotations();
      z_446 = annos138;
      term = t_342.invoke(context, y_446);
      if(term == null)
        break Fail892;
      term = termFactory.annotateTerm(termFactory.makeAppl(ext._consDefaultVarDec_1, new IStrategoTerm[]{term}), checkListAnnos(termFactory, z_446));
      context.popOnSuccess();
      if(true)
        return term;
    }
    context.popOnFailure();
    return null;
  }
}