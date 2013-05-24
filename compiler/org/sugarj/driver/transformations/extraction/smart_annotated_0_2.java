package org.sugarj.driver.transformations.extraction;

import org.strategoxt.stratego_lib.*;
import org.strategoxt.lang.*;
import org.spoofax.interpreter.terms.*;
import static org.strategoxt.lang.Term.*;
import org.spoofax.interpreter.library.AbstractPrimitive;
import java.util.ArrayList;
import java.lang.ref.WeakReference;

@SuppressWarnings("all") public class smart_annotated_0_2 extends Strategy 
{ 
  public static smart_annotated_0_2 instance = new smart_annotated_0_2();

  @Override public IStrategoTerm invoke(Context context, IStrategoTerm term, IStrategoTerm ref_i_326, IStrategoTerm ref_j_326)
  { 
    ITermFactory termFactory = context.getFactory();
    TermReference i_326 = new TermReference(ref_i_326);
    TermReference j_326 = new TermReference(ref_j_326);
    context.push("smart_annotated_0_2");
    Fail692:
    { 
      IStrategoTerm k_326 = null;
      IStrategoTerm v_326 = null;
      IStrategoTerm x_326 = null;
      term = extraction.constNil0;
      lifted473 lifted4730 = new lifted473();
      lifted4730.i_326 = i_326;
      lifted4730.j_326 = j_326;
      term = try_1_0.instance.invoke(context, term, lifted4730);
      if(term == null)
        break Fail692;
      k_326 = term;
      if(i_326.value == null || j_326.value == null)
        break Fail692;
      term = (IStrategoTerm)termFactory.makeListCons(i_326.value, termFactory.makeListCons(j_326.value, (IStrategoList)extraction.constNil0));
      v_326 = term;
      term = context.invokePrimitive("SUGARJ_unsafe_build", v_326, NO_STRATEGIES, new IStrategoTerm[]{extraction.const640});
      if(term == null)
        break Fail692;
      x_326 = term;
      term = build_alt_sort_or_fail_0_0.instance.invoke(context, k_326);
      if(term == null)
        break Fail692;
      term = put_syntax_sort_0_1.instance.invoke(context, x_326, term);
      if(term == null)
        break Fail692;
      context.popOnSuccess();
      if(true)
        return term;
    }
    context.popOnFailure();
    return null;
  }
}