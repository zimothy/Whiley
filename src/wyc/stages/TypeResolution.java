// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyc.stages;

import java.util.*;

import static wyil.util.SyntaxError.*;
import static wyil.util.ErrorMessages.*;
import wyil.ModuleLoader;
import wyil.util.*;
import wyil.lang.*;
import wyc.lang.*;
import wyc.lang.WhileyFile.*;
import wyc.lang.Stmt;
import wyc.lang.Stmt.*;

/**
 * <p>
 * Responsible for expanding all types and constraints for a given module(s).
 * For example, consider these two declarations:
 * </p>
 * 
 * <pre>
 * define Point2D as {int x, int y}
 * define Point3D as {int x, int y, int z}
 * define Point as Point2D | Point3D
 * </pre>
 * <p>
 * This stage will expand the type <code>Point</code> to give its full
 * structural definition. That is,
 * <code>{int x,int y}|{int x,int y,int z}</code>.
 * </p>
 * <p>
 * Type expansion must also account for any constraints on the types in
 * question. For example:
 * </p>
 * 
 * <pre>
 * define nat as int where $ >= 0
 * define natlist as [nat]
 * </pre>
 * <p>
 * The type <code>natlist</code> expands to <code>[int]</code>, whilst its
 * constraint is expanded to <code>all {x in $ | x >= 0}</code>.
 * </p>
 * 
 * @author David J. Pearce
 * 
 */
public final class TypeResolution {
	private final ModuleLoader loader;	
	
	// The shadow set is used to (efficiently) aid the correct generation of
	// runtime checks for post conditions. The key issue is that a post
	// condition may refer to parameters of the method. However, if those
	// parameters are modified during the method, then we must store their
	// original value on entry for use in the post-condition runtime check.
	// These stored values are called "shadows".
	private final HashMap<String, Integer> shadows = new HashMap<String, Integer>();

	public TypeResolution(ModuleLoader loader) {
		this.loader = loader;		
	}

	public void resolve(List<WhileyFile> files) {
		modules = new HashSet<ModuleID>();
		filemap = new HashMap<NameID, WhileyFile>();		
		types = new HashMap<NameID, Pair<Type,Block>>();
		constants = new HashMap<NameID, Value>();
		unresolved = new HashMap<NameID, Pair<UnresolvedType,Expr>>();

		// now, init data
		for (WhileyFile f : files) {
			modules.add(f.module);
		}

		// Stage 1 ... resolve and check types of all named types + constants
		generateConstants(files);
		generateTypes(files);

		// Stage 3 ... resolve, propagate types for all expressions		
		for (WhileyFile f : files) {
			resolve(f);
		}
	}

	private void resolve(WhileyFile wf) {
		this.filename = wf.filename;
		
		for (WhileyFile.Decl d : wf.declarations) {
			try {
				if (d instanceof TypeDecl) {
					resolve((TypeDecl) d, wf.module);
				} else if (d instanceof ConstDecl) {
					resolve((ConstDecl) d, wf.module);
				} else if (d instanceof FunDecl) {
					resolve((FunDecl) d);					
				}
			} catch (SyntaxError se) {
				throw se;
			} catch (Throwable ex) {
				internalFailure("internal failure", wf.filename, d, ex);
			}
		}				
	}

	/**
	 * The following method visits every define constant statement in every
	 * whiley file being compiled, and determines its true and value.
	 * 
	 * @param files
	 */
	private void generateConstants(List<WhileyFile> files) {
		HashMap<NameID, Expr> exprs = new HashMap();

		// first construct list.
		for (WhileyFile f : files) {
			for (Decl d : f.declarations) {
				if (d instanceof ConstDecl) {
					ConstDecl cd = (ConstDecl) d;
					NameID key = new NameID(f.module, cd.name());
					exprs.put(key, cd.constant);
					filemap.put(key, f);
				}
			}
		}

		for (NameID k : exprs.keySet()) {
			try {
				Value v = expandConstant(k, exprs, new HashSet<NameID>());
				constants.put(k, v);
				Type t = v.type();
				if (t instanceof Type.Set) {
					Type.Set st = (Type.Set) t;
					String label = Block.freshLabel();
					Collection<Attribute> attributes = attributes(exprs.get(k));
					Block blk = new Block(1);
					blk.append(Code.Load(st.element(), 0),attributes);
					blk.append(Code.Const(v),attributes);					
					blk.append(Code.IfGoto(st, Code.COp.ELEMOF, label));
					blk.append(Code.Fail("constraint on type not satisfied (" + k + ")"),attributes);
					blk.append(Code.Label(label),attributes);
					types.put(k, new Pair<Type,Block>(st.element(),blk));
				}
			} catch (ResolveError rex) {
				syntaxError(rex.getMessage(), filemap.get(k).filename, exprs
						.get(k), rex);
			}
		}
	}

	/**
	 * The expand constant method is responsible for turning a named constant
	 * expression into a value. This is done by traversing the constant's
	 * expression and recursively expanding any named constants it contains.
	 * Simplification of constants is also performed where possible.
	 * 
	 * @param key
	 *            --- name of constant we are expanding.
	 * @param exprs
	 *            --- mapping of all names to their( declared) expressions
	 * @param visited
	 *            --- set of all constants seen during this traversal (used to
	 *            detect cycles).
	 * @return
	 * @throws ResolveError
	 */
	private Value expandConstant(NameID key, HashMap<NameID, Expr> exprs,
			HashSet<NameID> visited) throws ResolveError {
		Expr e = exprs.get(key);
		Value value = constants.get(key);
		if (value != null) {
			return value;
		} else if (!modules.contains(key.module())) {
			// indicates a non-local key
			Module mi = loader.loadModule(key.module());
			return mi.constant(key.name()).constant();
		} else if (visited.contains(key)) {
			// this indicates a cyclic definition.
			String errMsg = errorMessage(CYCLIC_CONSTANT_DECLARATION);
			syntaxError(errMsg, filemap
					.get(key).filename, exprs.get(key));
		} else {
			visited.add(key); // mark this node as visited
		}

		// At this point, we need to replace every unresolved variable with a
		// constant definition.
		Value v = expandConstantHelper(e, filemap.get(key).filename, exprs,
				visited);
		constants.put(key, v);
		return v;
	}

	/**
	 * The following is a helper method for expandConstant. It takes a given
	 * expression (rather than the name of a constant) and expands to a value
	 * (where possible). If the expression contains, for example, method or
	 * function declarations then this will certainly fail (producing a syntax
	 * error).
	 * 
	 * @param key
	 *            --- name of constant we are expanding.
	 * @param exprs
	 *            --- mapping of all names to their( declared) expressions
	 * @param visited
	 *            --- set of all constants seen during this traversal (used to
	 *            detect cycles).
	 */
	private Value expandConstantHelper(Expr expr, String filename,
			HashMap<NameID, Expr> exprs, HashSet<NameID> visited)
			throws ResolveError {
		if (expr instanceof Expr.Constant) {
			Expr.Constant c = (Expr.Constant) expr;
			return c.value;
		} else if (expr instanceof Expr.ExternalAccess) {
			Expr.ExternalAccess v = (Expr.ExternalAccess) expr;			
			return expandConstant(v.nid, exprs, visited);
		} else if (expr instanceof Expr.BinOp) {
			Expr.BinOp bop = (Expr.BinOp) expr;
			Value lhs = expandConstantHelper(bop.lhs, filename, exprs, visited);
			Value rhs = expandConstantHelper(bop.rhs, filename, exprs, visited);
			return evaluate(bop, lhs, rhs);			
		} else if (expr instanceof Expr.NaryOp) {
			Expr.NaryOp nop = (Expr.NaryOp) expr;
			ArrayList<Value> values = new ArrayList<Value>();
			for (Expr arg : nop.arguments) {
				values.add(expandConstantHelper(arg, filename, exprs, visited));
			}
			if (nop.nop == Expr.NOp.LISTGEN) {
				return Value.V_LIST(values);
			} else if (nop.nop == Expr.NOp.SETGEN) {
				return Value.V_SET(values);
			}
		} else if (expr instanceof Expr.RecordGen) {
			Expr.RecordGen rg = (Expr.RecordGen) expr;
			HashMap<String,Value> values = new HashMap<String,Value>();
			for(Map.Entry<String,Expr> e : rg.fields.entrySet()) {
				Value v = expandConstantHelper(e.getValue(),filename,exprs,visited);
				if(v == null) {
					return null;
				}
				values.put(e.getKey(), v);
			}
			return Value.V_RECORD(values);
		} else if (expr instanceof Expr.TupleGen) {
			Expr.TupleGen rg = (Expr.TupleGen) expr;			
			ArrayList<Value> values = new ArrayList<Value>();			
			for(Expr e : rg.fields) {
				Value v = expandConstantHelper(e,filename,exprs,visited);
				if(v == null) {
					return null;
				}
				values.add(v);				
			}
			return Value.V_TUPLE(values);
		}  else if (expr instanceof Expr.DictionaryGen) {
			Expr.DictionaryGen rg = (Expr.DictionaryGen) expr;			
			HashSet<Pair<Value,Value>> values = new HashSet<Pair<Value,Value>>();			
			for(Pair<Expr,Expr> e : rg.pairs) {
				Value key = expandConstantHelper(e.first(),filename,exprs,visited);
				Value value = expandConstantHelper(e.second(),filename,exprs,visited);
				if(key == null || value == null) {
					return null;
				}
				values.add(new Pair<Value,Value>(key,value));				
			}
			return Value.V_DICTIONARY(values);
		} else if(expr instanceof Expr.Function) {
			Expr.Function f = (Expr.Function) expr;
			Attributes.Module mid = expr.attribute(Attributes.Module.class);
			if (mid != null) {
				NameID name = new NameID(mid.module, f.name);
				Type.Function tf = null;
				
				if(f.paramTypes != null) {
					ArrayList<Type> paramTypes = new ArrayList<Type>();
					for(UnresolvedType p : f.paramTypes) {
						// TODO: fix parameter constraints
						resolve(p);
						paramTypes.add(p.attribute(Attributes.Type.class).type);
					}				
					tf = checkType(
							Type.Function(Type.T_ANY, Type.T_VOID, paramTypes),
							Type.Function.class, expr);
				}
				
				return Value.V_FUN(name, tf);	
			}					
		}
		syntaxError(errorMessage(INVALID_CONSTANT_EXPRESSION), filename, expr);
		return null;
	}

	private Value evaluate(Expr.BinOp bop, Value v1, Value v2) {
		Type lub = Type.Union(v1.type(), v2.type());
		
		// FIXME: there are bugs here related to coercions.
		
		if(Type.isSubtype(Type.T_BOOL, lub)) {
			return evaluateBoolean(bop,(Value.Bool) v1,(Value.Bool) v2);
		} else if(Type.isSubtype(Type.T_REAL, lub)) {
			return evaluate(bop,(Value.Rational) v1, (Value.Rational) v2);
		} else if(Type.isSubtype(Type.List(Type.T_ANY, false), lub)) {
			return evaluate(bop,(Value.List)v1,(Value.List)v2);
		} else if(Type.isSubtype(Type.Set(Type.T_ANY, false), lub)) {
			return evaluate(bop,(Value.Set) v1, (Value.Set) v2);
		} 
		syntaxError(errorMessage(INVALID_BINARY_EXPRESSION),filename,bop);
		return null;
	}
	
	private Value evaluateBoolean(Expr.BinOp bop, Value.Bool v1, Value.Bool v2) {				
		switch(bop.op) {
		case AND:
			return Value.V_BOOL(v1.value & v2.value);
		case OR:		
			return Value.V_BOOL(v1.value | v2.value);
		case XOR:
			return Value.V_BOOL(v1.value ^ v2.value);
		}
		syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION),filename,bop);
		return null;
	}
	
	private Value evaluate(Expr.BinOp bop, Value.Rational v1, Value.Rational v2) {		
		switch(bop.op) {
		case ADD:
			return Value.V_RATIONAL(v1.value.add(v2.value));
		case SUB:
			return Value.V_RATIONAL(v1.value.subtract(v2.value));
		case MUL:
			return Value.V_RATIONAL(v1.value.multiply(v2.value));
		case DIV:
			return Value.V_RATIONAL(v1.value.divide(v2.value));
		case REM:
			return Value.V_RATIONAL(v1.value.intRemainder(v2.value));	
		}
		syntaxError(errorMessage(INVALID_NUMERIC_EXPRESSION),filename,bop);
		return null;
	}
	
	private Value evaluate(Expr.BinOp bop, Value.List v1, Value.List v2) {
		switch(bop.op) {
		case ADD:
			ArrayList<Value> vals = new ArrayList<Value>(v1.values);
			vals.addAll(v2.values);
			return Value.V_LIST(vals);
		}
		syntaxError(errorMessage(INVALID_LIST_EXPRESSION),filename,bop);
		return null;
	}
	
	private Value evaluate(Expr.BinOp bop, Value.Set v1, Value.Set v2) {		
		switch(bop.op) {
		case UNION:
		{
			HashSet<Value> vals = new HashSet<Value>(v1.values);			
			vals.addAll(v2.values);
			return Value.V_SET(vals);
		}
		case INTERSECTION:
		{
			HashSet<Value> vals = new HashSet<Value>();			
			for(Value v : v1.values) {
				if(v2.values.contains(v)) {
					vals.add(v);
				}
			}			
			return Value.V_SET(vals);
		}
		case SUB:
		{
			HashSet<Value> vals = new HashSet<Value>();			
			for(Value v : v1.values) {
				if(!v2.values.contains(v)) {
					vals.add(v);
				}
			}			
			return Value.V_SET(vals);
		}
		}
		syntaxError(errorMessage(INVALID_SET_EXPRESSION),filename,bop);
		return null;
	}
	
	/**
	 * The following method visits every define type statement in every whiley
	 * file being compiled, and determines its true type.
	 * 
	 * @param files
	 */
	private void generateTypes(List<WhileyFile> files) {
		HashMap<NameID, SyntacticElement> srcs = new HashMap<NameID, SyntacticElement>();
		
		// The declOrder list is basically a hack. It ensures that types are
		// visited in the order that they are declared. This helps give some
		// sense to the way recursive types are handled, but a more general
		// solution could easily be found.
		ArrayList<NameID> declOrder = new ArrayList<NameID>();
		
		// second construct list.
		for (WhileyFile f : files) {
			for (Decl d : f.declarations) {
				if (d instanceof TypeDecl) {
					TypeDecl td = (TypeDecl) d;					
					NameID key = new NameID(f.module, td.name());
					declOrder.add(key);
					unresolved.put(key, new Pair<UnresolvedType,Expr>(td.type,td.constraint));
					srcs.put(key, d);
					filemap.put(key, f);
				} else if (d instanceof ConstDecl) {
					ConstDecl td = (ConstDecl) d;					
					NameID key = new NameID(f.module, td.name());									
					srcs.put(key, d);
					filemap.put(key, f);
				}
			}
		}

		// third expand all types
		for (NameID key : declOrder) {			
			try {
				HashMap<NameID, Type> cache = new HashMap<NameID, Type>();				
				Pair<Type, Block> p = expandType(key, cache,
						filemap.get(key).filename, srcs.get(key));	
				
				types.put(key, new Pair<Type,Block>(p.first(),p.second()));				
			} catch (ResolveError ex) {
				syntaxError(ex.getMessage(), filemap.get(key).filename, srcs
						.get(key), ex);
			}
		}
	}
		
	/**
	 * This is a deeply complex method!
	 * 
	 * @param key
	 * @param cache
	 * @return A triple of the form <T,B,C>, where T is the type, B is the
	 *         constraint block and C indicates whether or not this is in fact a
	 *         constrained type. The latter is useful since it means we can
	 *         throw away unnecessary constraint blocks when the type in
	 *         question is not actually constrained.
	 * @throws ResolveError
	 */
	private Pair<Type, Block> expandType(NameID key,
			HashMap<NameID, Type> cache, String filename, SyntacticElement elem)
			throws ResolveError {
				
		Type cached = cache.get(key);
		Pair<Type,Block> t = types.get(key);
		
		if (cached != null) {						
			return new Pair<Type,Block>(cached, null);
		} else if(t != null) {						
			return new Pair<Type,Block>(t.first(),t.second());
		} else if (!modules.contains(key.module())) {			
			// indicates a non-local key which we can resolve immediately
			Module mi = loader.loadModule(key.module());
			Module.TypeDef td = mi.type(key.name());	
			return new Pair<Type,Block>(td.type(),td.constraint());
		} else if(constants.containsKey(key)) {
			// this case happen if the key corresponds to a constant declared in
			// the file which is not, in fact, a type. 
			syntaxError(errorMessage(ErrorMessages.INVALID_CONSTANT_AS_TYPE),
					filename, elem);
		} else if(!unresolved.containsKey(key)) {
			// this case happen if the key corresponds to a function or method declared in
			// the file (which is definitely not a type).
			syntaxError(
					errorMessage(ErrorMessages.INVALID_FUNCTION_OR_METHOD_AS_TYPE),
					filename, elem);
		}

		// following is needed to terminate any recursion
		cache.put(key, Type.Nominal(key));

		// now, expand the type fully		
		Pair<UnresolvedType,Expr> ut = unresolved.get(key); 
		t = expandType(ut.first(), filemap.get(key).filename,
				cache);

		// Now, we need to test whether the current type is open and recursive
		// on this name. In such case, we must close it in order to complete the
		// recursive type.
		boolean isOpenRecursive = Type.isOpen(key, t.first());
		if (isOpenRecursive) {
			t = new Pair<Type, Block>(Type.Recursive(key,
					t.first()), t.second());
		}
		
		Block blk = t.second();
		if (ut.second() != null) {
			String trueLabel = Block.freshLabel();
			HashMap<String,Integer> environment = new HashMap<String,Integer>();
			environment.put("$", Code.THIS_SLOT);			
			Block constraint = resolveCondition(trueLabel, ut.second(), environment);
			constraint.append(Code.Fail("constraint on type not satisfied (" + key + ")"), attributes(ut
					.second()));
			constraint.append(Code.Label(trueLabel));

			if (blk == null) { 
				t = new Triple<Type, Block, Boolean>(t.first(), constraint, true);
			} else {
				blk.append(constraint); 
				t = new Triple<Type, Block, Boolean>(t.first(), blk, true);
			}
		}
		
		// finally, store it in the cache
		cache.put(key, t.first());

		// Done
		return t;
	}

	private Pair<Type,Block> expandType(UnresolvedType t, String filename,
			HashMap<NameID, Type> cache) {
		if (t instanceof UnresolvedType.List) {
			UnresolvedType.List lt = (UnresolvedType.List) t;
			Pair<Type,Block> p = expandType(lt.element, filename, cache);			
			Block blk = null;
			if (p.second() != null) {
				blk = new Block(1); 
				String label = Block.freshLabel();
				blk.append(Code.Load(null, Code.THIS_SLOT), attributes(t));
				blk.append(Code.ForAll(null, Code.THIS_SLOT + 1, label,
						Collections.EMPTY_LIST), attributes(t));
				blk.append(shiftBlock(1,p.second()));				
				blk.append(Code.End(label));
			}		
			return new Pair<Type,Block>(Type.List(p.first(), false),blk);			
		} else if (t instanceof UnresolvedType.Set) {
			UnresolvedType.Set st = (UnresolvedType.Set) t;
			Pair<Type,Block> p = expandType(st.element, filename, cache);
			Block blk = null;
			if (p.second() != null) {
				blk = new Block(1); 
				String label = Block.freshLabel();
				blk.append(Code.Load(null, Code.THIS_SLOT), attributes(t));
				blk.append(Code.ForAll(null, Code.THIS_SLOT + 1, label,
						Collections.EMPTY_LIST), attributes(t));
				blk.append(shiftBlock(1,p.second()));				
				blk.append(Code.End(label));
			}						
			return new Pair<Type,Block>(Type.Set(p.first(),false),blk);					
		} else if (t instanceof UnresolvedType.Dictionary) {
			UnresolvedType.Dictionary st = (UnresolvedType.Dictionary) t;	
			Block blk = null;
			// FIXME: put in constraints.  REQUIRES ITERATION OVER DICTIONARIES
			Pair<Type,Block> key = expandType(st.key, filename, cache);
			Pair<Type,Block> value = expandType(st.value, filename, cache);
			return new Pair<Type,Block>(Type.Dictionary(key.first(),value.first()),blk);					
		} else if (t instanceof UnresolvedType.Tuple) {
			// At the moment, a tuple is compiled down to a wyil record.
			UnresolvedType.Tuple tt = (UnresolvedType.Tuple) t;
			Block blk = null;						
			ArrayList<Type> types = new ArrayList<Type>();				
			
			int i=0;			
			for (UnresolvedType e : tt.types) {				
				Pair<Type,Block> p = expandType(e, filename, cache);
				types.add(p.first());
				if(p.second() != null) {					
					if(blk == null) {
						blk = new Block(1);
					}					
					blk.append(Code.Load(null, Code.THIS_SLOT), attributes(t));
					blk.append(Code.TupleLoad(null, i), attributes(t));
					blk.append(Code.Store(null, Code.THIS_SLOT+1), attributes(t));
					blk.append(shiftBlock(1,p.second()));
				}
				i=i+1;
			}			
			
			return new Pair<Type,Block>(Type.Tuple(types),blk);			
		} else if (t instanceof UnresolvedType.Record) {
			UnresolvedType.Record tt = (UnresolvedType.Record) t;
			Block blk = null;
			HashMap<String, Type> types = new HashMap<String, Type>();								
			for (Map.Entry<String, UnresolvedType> e : tt.types.entrySet()) {
				Pair<Type,Block> p = expandType(e.getValue(), filename, cache); 
				if (p.second() != null) {
					if(blk == null) {
						blk = new Block(1);
					}					
					blk.append(Code.Load(null, Code.THIS_SLOT), attributes(t));
					blk.append(Code.FieldLoad(null, e.getKey()), attributes(t));
					blk.append(Code.Store(null, Code.THIS_SLOT+1), attributes(t));
					blk.append(shiftBlock(1,p.second()));								
				}	
				types.put(e.getKey(), p.first());				
			}
			return new Pair<Type, Block>(Type.Record(tt.isOpen, types), blk);						
		} else if (t instanceof UnresolvedType.Union) {
			UnresolvedType.Union ut = (UnresolvedType.Union) t;
			HashSet<Type> bounds = new HashSet<Type>();
			Block blk = new Block(1);
			String exitLabel = Block.freshLabel();
			boolean constraints = false;
			List<UnresolvedType.NonUnion> ut_bounds = ut.bounds;			
			for (int i=0;i!=ut_bounds.size();++i) {
				boolean lastBound = (i+1) == ut_bounds.size(); 
				UnresolvedType b = ut_bounds.get(i);
				Pair<Type,Block> p = expandType(b, filename, cache);
				Type bt = p.first();
 
				bounds.add(bt);	
				
				if(p.second() != null) {
					// In this case, there are constraints so we check the
					// negated type and branch over the constraint test if we
					// don't have the require type.  
					
					// TODO: in principle, the following should work. However,
					// it's broken in the case of recurisve types being used in
					// the type tests. This should be resolvable when we support
					// proper named types, since we won't be expanding fully at
					// this stage.
//					constraints = true;
//					String nextLabel = Block.freshLabel();												
//					blk.append(
//							Code.IfType(null, Code.THIS_SLOT,
//									Type.Negation(bt), nextLabel),
//									attributes(t));					
//					blk.append(chainBlock(nextLabel,p.second()));
//					blk.append(Code.Goto(exitLabel));
//					blk.append(Code.Label(nextLabel));
				} else {	
					// In this case, there are no constraints so we can use a
					// direct type test.
					blk.append(
							Code.IfType(null, Code.THIS_SLOT, bt, exitLabel),
							attributes(t));
				}
			}
			
			if(constraints) {
				blk.append(Code.Fail("type constraint not satisfied"),attributes(ut));
				blk.append(Code.Label(exitLabel));
			} else {
				blk = null;
			}
			
			if (bounds.size() == 1) {
				return new Pair<Type,Block>(bounds.iterator().next(),blk);
			} else {				
				return new Pair<Type,Block>(Type.Union(bounds),blk);
			}			
		} else if (t instanceof UnresolvedType.Not) {
			UnresolvedType.Not st = (UnresolvedType.Not) t;
			Pair<Type,Block> p = expandType(st.element, filename, cache);
			Block blk = null;
			// TODO: need to fix not constraints					
			return new Pair<Type,Block>(Type.Negation(p.first()),blk);					
		} else if (t instanceof UnresolvedType.Intersection) {
			UnresolvedType.Intersection ut = (UnresolvedType.Intersection) t;
			Block blk = null;
			Type r = null;
			for(int i=0;i!=ut.bounds.size();++i) {
				UnresolvedType b = ut.bounds.get(i);
				Pair<Type,Block> p = expandType(b, filename, cache);
				// TODO: add intersection constraints
				if(r == null) {
					r = p.first();
				} else {
					r = Type.intersect(p.first(),r);
				}
			}						
			return new Pair<Type,Block>(r,blk);					
		} else if(t instanceof UnresolvedType.Existential) {
			UnresolvedType.Existential ut = (UnresolvedType.Existential) t;			
			ModuleID mid = ut.attribute(Attributes.Module.class).module;			
			// TODO: need to fix existentials
			return new Pair<Type,Block>(Type.Nominal(new NameID(mid,"1")),null);							
		} else if (t instanceof UnresolvedType.Process) {
			UnresolvedType.Process ut = (UnresolvedType.Process) t;
			Block blk = null;
			Pair<Type,Block> p = expandType(ut.element, filename, cache);
			// TODO: fix process constraints
			return new Pair<Type,Block>(Type.Process(p.first()),blk);							
		} else if (t instanceof UnresolvedType.Named) {
			UnresolvedType.Named dt = (UnresolvedType.Named) t;
			Attributes.Name nameInfo = dt.attribute(Attributes.Name.class);
			NameID name = nameInfo.name;

			try {
				// need to check for existential case				
				return expandType(name, cache, filename, dt);															
			} catch (ResolveError rex) {
				syntaxError(rex.getMessage(), filename, t, rex);
				return null;
			}
		} if (t instanceof UnresolvedType.Any) {
			return new Pair<Type,Block>(Type.T_ANY,null);
		} else if (t instanceof UnresolvedType.Void) {
			return new Pair<Type,Block>(Type.T_VOID,null);
		} else if (t instanceof UnresolvedType.Null) {
			return new Pair<Type,Block>(Type.T_NULL,null);
		} else if (t instanceof UnresolvedType.Bool) {
			return new Pair<Type,Block>(Type.T_BOOL,null);
		} else if (t instanceof UnresolvedType.Byte) {
			return new Pair<Type,Block>(Type.T_BYTE,null);
		} else if (t instanceof UnresolvedType.Char) {
			return new Pair<Type,Block>(Type.T_CHAR,null);
		} else if (t instanceof UnresolvedType.Int) {
			return new Pair<Type,Block>(Type.T_INT,null);
		} else if (t instanceof UnresolvedType.Real) {
			return new Pair<Type,Block>(Type.T_REAL,null);
		} else if (t instanceof UnresolvedType.Strung) {
			return new Pair<Type,Block>(Type.T_STRING,null);
		} else {
			internalFailure("unknown type encountered",filename,t);
			return null; // dead code
		}
	}

	private void resolve(ConstDecl td, ModuleID module) {
		Value v = constants.get(new NameID(module, td.name()));
		td.attributes().add(new Attributes.Constant(v));		
	}

	private void resolve(TypeDecl td, ModuleID module) {
		Pair<Type,Block> p = types.get(new NameID(module, td.name()));
		td.attributes().add(new Attributes.Type(p.first(), p.second()));		
	}

	private void resolve(FunDecl fd) {		
		HashMap<String,Integer> environment = new HashMap<String,Integer>();
		
		// method return type
		resolve(fd.ret);
		int paramIndex = 0;
		int nparams = fd.parameters.size();
		// method receiver type (if applicable)
		if (fd instanceof MethDecl) {
			MethDecl md = (MethDecl) fd;
			if(md.receiver != null) {
				resolve(md.receiver);
				// TODO: fix receiver constraints
				environment.put("this", paramIndex++);	
				nparams++;
			}
		}
		
		// ==================================================================
		// Generate pre-condition
		// ==================================================================
		Block precondition = null;
		
		for (WhileyFile.Parameter p : fd.parameters) {			
			// First, resolve and inline any constraints associated with the type.
			resolve(p.type);
			Block constraint = t.second();
			if(constraint != null) {
				if(precondition == null) {
					precondition = new Block(nparams);
				}				
				HashMap<Integer,Integer> binding = new HashMap<Integer,Integer>();
				binding.put(0,paramIndex);			
				precondition.importExternal(constraint,binding);
			}
			// Now, map the parameter to its index
			environment.put(p.name(),paramIndex++);
		}		
		// Resolve pre- and post-condition								
		if(fd.precondition != null) {
			if(precondition == null) {
				precondition = new Block(nparams);	
			}
			String lab = Block.freshLabel();
			HashMap<String,Integer> preEnv = new HashMap<String,Integer>(environment);						
			precondition.append(resolveCondition(lab, fd.precondition, preEnv));		
			precondition.append(Code.Fail("precondition not satisfied"), attributes(fd.precondition));
			precondition.append(Code.Label(lab));			
		}
		
		// ==================================================================
		// Generate post-condition
		// ==================================================================		
		HashMap<String,Integer> postEnv = new HashMap<String,Integer>();
		postEnv.put("$", 0);
		for(String var : environment.keySet()) {
			postEnv.put(var, environment.get(var)+1);
		}
		resolve(fd.ret);						
		
		// ==================================================================
		// Generate body
		// ==================================================================
		currentFunDecl = fd;
						
		for (Stmt s : fd.statements) {
			resolve(s, environment);
		}

		currentFunDecl = null;
	}

	/**
	 * Translate a source-level statement into a wyil block, using a given
	 * environment mapping named variables to slots.
	 * 
	 * @param stmt
	 *            --- statement to be translated.
	 * @param environment
	 *            --- mapping from variable names to to slot numbers.
	 * @return
	 */
	private Block resolve(Stmt stmt, HashMap<String,Integer> environment) {
		try {
			if (stmt instanceof Assign) {
				return resolve((Assign) stmt, environment);
			} else if (stmt instanceof Assert) {
				return resolve((Assert) stmt, environment);
			} else if (stmt instanceof Return) {
				return resolve((Return) stmt, environment);
			} else if (stmt instanceof Debug) {
				return resolve((Debug) stmt, environment);
			} else if (stmt instanceof IfElse) {
				return resolve((IfElse) stmt, environment);
			} else if (stmt instanceof Switch) {
				return resolve((Switch) stmt, environment);
			} else if (stmt instanceof TryCatch) {
				return resolve((TryCatch) stmt, environment);
			} else if (stmt instanceof Break) {
				return resolve((Break) stmt, environment);
			} else if (stmt instanceof Throw) {
				return resolve((Throw) stmt, environment);
			} else if (stmt instanceof While) {
				return resolve((While) stmt, environment);
			} else if (stmt instanceof DoWhile) {
				return resolve((DoWhile) stmt, environment);
			} else if (stmt instanceof For) {
				return resolve((For) stmt, environment);
			} else if (stmt instanceof Expr.Invoke) {
				return resolve((Expr.Invoke) stmt,false,environment);								
			} else if (stmt instanceof Expr.Spawn) {
				return resolve((Expr.UnOp) stmt, environment);
			} else if (stmt instanceof Skip) {
				return resolve((Skip) stmt, environment);
			} else {
				// should be dead-code
				internalFailure("unknown statement encountered: "
						+ stmt.getClass().getName(), filename, stmt);
			}
		} catch (ResolveError rex) {
			syntaxError(rex.getMessage(), filename, stmt, rex);
		} catch (SyntaxError sex) {
			throw sex;
		} catch (Exception ex) {			
			internalFailure("internal failure", filename, stmt, ex);
		}
		return null;
	}
	
	private Block resolve(Assign s, HashMap<String,Integer> environment) {
		Block blk = null;
		
		if(s.lhs instanceof Expr.LocalVariable) {			
			blk = resolve(s.rhs, environment);			
			Expr.LocalVariable v = (Expr.LocalVariable) s.lhs;
			blk.append(Code.Store(null, allocate(v.var, environment)),
					attributes(s));			
		} else if(s.lhs instanceof Expr.TupleGen) {					
			Expr.TupleGen tg = (Expr.TupleGen) s.lhs;
			blk = resolve(s.rhs, environment);			
			blk.append(Code.Destructure(null),attributes(s));
			ArrayList<Expr> fields = new ArrayList<Expr>(tg.fields);
			Collections.reverse(fields);
			
			for(Expr e : fields) {
				if(!(e instanceof Expr.LocalVariable)) {
					syntaxError(errorMessage(INVALID_TUPLE_LVAL),filename,e);
				}
				Expr.LocalVariable v = (Expr.LocalVariable) e;
				blk.append(Code.Store(null, allocate(v.var, environment)),
						attributes(s));				
			}
			return blk;
		} else if(s.lhs instanceof Expr.ListAccess || s.lhs instanceof Expr.RecordAccess){
			// this is where we need a multistore operation						
			ArrayList<String> fields = new ArrayList<String>();
			blk = new Block(environment.size());
			Pair<Expr.LocalVariable,Integer> l = extractLVal(s.lhs,fields,blk,environment);
			if(!environment.containsKey(l.first().var)) {
				syntaxError("unknown variable",filename,l.first());
			}
			int slot = environment.get(l.first().var);
			blk.append(resolve(s.rhs, environment));			
			blk.append(Code.Update(null,null,slot,l.second(),fields),
					attributes(s));							
		} else {
			syntaxError("invalid assignment", filename, s);
		}
		
		return blk;
	}

	private Pair<Expr.LocalVariable, Integer> extractLVal(Expr e,
			ArrayList<String> fields, Block blk, 
			HashMap<String, Integer> environment) {
		if (e instanceof Expr.LocalVariable) {
			Expr.LocalVariable v = (Expr.LocalVariable) e;
			return new Pair(v,0);			
		} else if (e instanceof Expr.ListAccess) {
			Expr.ListAccess la = (Expr.ListAccess) e;
			Pair<Expr.LocalVariable,Integer> l = extractLVal(la.src, fields, blk, environment);
			blk.append(resolve(la.index, environment));			
			return new Pair(l.first(),l.second() + 1);
		} else if (e instanceof Expr.RecordAccess) {
			Expr.RecordAccess ra = (Expr.RecordAccess) e;
			Pair<Expr.LocalVariable,Integer> l = extractLVal(ra.lhs, fields, blk, environment);
			fields.add(ra.name);
			return new Pair(l.first(),l.second() + 1);			
		} else {
			syntaxError(errorMessage(INVALID_LVAL_EXPRESSION), filename, e);
			return null; // dead code
		}
	}
	
	private Block resolve(Assert s, HashMap<String,Integer> environment) {
		String lab = Block.freshLabel();
		Block blk = new Block(environment.size());
		blk.append(Code.Assert(lab),attributes(s));
		blk.append(resolveCondition(lab, s.expr, environment));		
		blk.append(Code.Fail("assertion failed"), attributes(s));
		blk.append(Code.Label(lab));			
		return blk;
	}

	private Block resolve(Return s, HashMap<String,Integer> environment) {

		if (s.expr != null) {
			Block blk = resolve(s.expr, environment);
			Pair<Type,Block> ret = resolve(currentFunDecl.ret);
			blk.append(Code.Return(ret.first()), attributes(s));
			return blk;			
		} else {
			Block blk = new Block(environment.size());
			blk.append(Code.Return(Type.T_VOID), attributes(s));
			return blk;
		}
	}

	private Block resolve(Skip s, HashMap<String,Integer> environment) {
		Block blk = new Block(environment.size());
		blk.append(Code.Skip, attributes(s));
		return blk;
	}

	private Block resolve(Debug s, HashMap<String,Integer> environment) {		
		Block blk = resolve(s.expr, environment);		
		blk.append(Code.debug, attributes(s));
		return blk;
	}

	private Block resolve(IfElse s, HashMap<String,Integer> environment) {
		String falseLab = Block.freshLabel();
		String exitLab = s.falseBranch.isEmpty() ? falseLab : Block
				.freshLabel();
		Block blk = resolveCondition(falseLab, invert(s.condition), environment);

		for (Stmt st : s.trueBranch) {
			blk.append(resolve(st, environment));
		}
		if (!s.falseBranch.isEmpty()) {
			blk.append(Code.Goto(exitLab));
			blk.append(Code.Label(falseLab));
			for (Stmt st : s.falseBranch) {
				blk.append(resolve(st, environment));
			}
		}

		blk.append(Code.Label(exitLab));

		return blk;
	}
	
	private Block resolve(Throw s, HashMap<String,Integer> environment) {
		Block blk = resolve(s.expr, environment);
		blk.append(Code.Throw(null), s.attributes());
		return blk;
	}
	
	private Block resolve(Break s, HashMap<String,Integer> environment) {
		BreakScope scope = findEnclosingScope(BreakScope.class);
		if(scope == null) {
			syntaxError(errorMessage(BREAK_OUTSIDE_LOOP), filename, s);
		}
		Block blk = new Block(environment.size());
		blk.append(Code.Goto(scope.label));
		return blk;
	}
	
	private Block resolve(Switch s, HashMap<String,Integer> environment) throws ResolveError {
		String exitLab = Block.freshLabel();		
		Block blk = resolve(s.expr, environment);				
		Block cblk = new Block(environment.size());
		String defaultTarget = exitLab;
		HashSet<Value> values = new HashSet();
		ArrayList<Pair<Value,String>> cases = new ArrayList();	
		
		for(Stmt.Case c : s.cases) {			
			if(c.values.isEmpty()) {
				// indicates the default block
				if(defaultTarget != exitLab) {
					syntaxError(errorMessage(DUPLICATE_DEFAULT_LABEL),filename,c);
				} else {
					defaultTarget = Block.freshLabel();	
					cblk.append(Code.Label(defaultTarget), attributes(c));
					for (Stmt st : c.stmts) {
						cblk.append(resolve(st, environment));
					}
					cblk.append(Code.Goto(exitLab),attributes(c));
				}
			} else if(defaultTarget == exitLab) {
				String target = Block.freshLabel();	
				cblk.append(Code.Label(target), attributes(c));				
				
				for(Expr e : c.values) { 
					Value constant = expandConstantHelper(e, filename,
							new HashMap(), new HashSet());												
					if(values.contains(constant)) {
						syntaxError(errorMessage(DUPLICATE_CASE_LABEL),filename,c);
					}									
					cases.add(new Pair(constant,target));
					values.add(constant);
				}
				
				for (Stmt st : c.stmts) {
					cblk.append(resolve(st, environment));
				}
				cblk.append(Code.Goto(exitLab),attributes(c));
			} else {
				syntaxError(errorMessage(UNREACHABLE_CODE), filename, c);
			}
		}		
		blk.append(Code.Switch(null,defaultTarget,cases),attributes(s));
		blk.append(cblk);
		blk.append(Code.Label(exitLab), attributes(s));		
		return blk;
	}
	
	private Block resolve(TryCatch s, HashMap<String,Integer> environment) throws ResolveError {
		String exitLab = Block.freshLabel();		
		Block cblk = new Block(environment.size());		
		for (Stmt st : s.body) {
			cblk.append(resolve(st, environment));
		}		
		cblk.append(Code.Goto(exitLab),attributes(s));	
		String endLab = null;
		ArrayList<Pair<Type,String>> catches = new ArrayList<Pair<Type,String>>();
		for(Stmt.Catch c : s.catches) {
			int freeReg = allocate(c.variable,environment);
			Code.Label lab;
			
			if(endLab == null) {
				endLab = Block.freshLabel();
				lab = Code.TryEnd(endLab);
			} else {
				lab = Code.Label(Block.freshLabel());
			}
			Pair<Type,Block> pt = resolve(c.type);
			// TODO: deal with exception type constraints
			catches.add(new Pair<Type,String>(pt.first(),lab.label));
			cblk.append(lab, attributes(c));
			cblk.append(Code.Store(pt.first(), freeReg), attributes(c));
			for (Stmt st : c.stmts) {
				cblk.append(resolve(st, environment));
			}
			cblk.append(Code.Goto(exitLab),attributes(c));
		}
		
		Block blk = new Block(environment.size());
		blk.append(Code.TryCatch(endLab,catches),attributes(s));
		blk.append(cblk);
		blk.append(Code.Label(exitLab), attributes(s));
		return blk;
	}
	
	private Block resolve(While s, HashMap<String,Integer> environment) {		
		String label = Block.freshLabel();									
				
		Block blk = new Block(environment.size());
		
		
		if(s.invariant != null) {
			String invariantLabel = Block.freshLabel();
			blk.append(Code.Assert(invariantLabel),attributes(s));
			blk.append(resolveCondition(invariantLabel, s.invariant, environment));		
			blk.append(Code.Fail("loop invariant not satisfied on entry"), attributes(s));
			blk.append(Code.Label(invariantLabel));			
		}
		
		blk.append(Code.Loop(label, Collections.EMPTY_SET),
				attributes(s));
				
		blk.append(resolveCondition(label, invert(s.condition), environment));

		scopes.push(new BreakScope(label));		
		for (Stmt st : s.body) {
			blk.append(resolve(st, environment));
		}		
		scopes.pop(); // break
		
		if(s.invariant != null) {
			String invariantLabel = Block.freshLabel();
			blk.append(Code.Assert(invariantLabel),attributes(s));
			blk.append(resolveCondition(invariantLabel, s.invariant, environment));		
			blk.append(Code.Fail("loop invariant not restored"), attributes(s));
			blk.append(Code.Label(invariantLabel));			
		}
		
		blk.append(Code.End(label));

		return blk;
	}

	private Block resolve(DoWhile s, HashMap<String,Integer> environment) {		
		String label = Block.freshLabel();				
				
		Block blk = new Block(environment.size());
		
		if(s.invariant != null) {
			String invariantLabel = Block.freshLabel();
			blk.append(Code.Assert(invariantLabel),attributes(s));
			blk.append(resolveCondition(invariantLabel, s.invariant, environment));		
			blk.append(Code.Fail("loop invariant not satisfied on entry"), attributes(s));
			blk.append(Code.Label(invariantLabel));			
		}
		
		blk.append(Code.Loop(label, Collections.EMPTY_SET),
				attributes(s));
		
		scopes.push(new BreakScope(label));	
		for (Stmt st : s.body) {
			blk.append(resolve(st, environment));
		}		
		scopes.pop(); // break
		
		if(s.invariant != null) {
			String invariantLabel = Block.freshLabel();
			blk.append(Code.Assert(invariantLabel),attributes(s));
			blk.append(resolveCondition(invariantLabel, s.invariant, environment));		
			blk.append(Code.Fail("loop invariant not restored"), attributes(s));
			blk.append(Code.Label(invariantLabel));			
		}
		
		blk.append(resolveCondition(label, invert(s.condition), environment));

		
		blk.append(Code.End(label));

		return blk;
	}
	
	private Block resolve(For s, HashMap<String,Integer> environment) {		
		String label = Block.freshLabel();
		
		Block blk = new Block(1);
		
		if(s.invariant != null) {
			String invariantLabel = Block.freshLabel();
			blk.append(Code.Assert(invariantLabel),attributes(s));
			blk.append(resolveCondition(invariantLabel, s.invariant, environment));		
			blk.append(Code.Fail("loop invariant not satisfied on entry"), attributes(s));
			blk.append(Code.Label(invariantLabel));			
		}
		
		blk.append(resolve(s.source,environment));	
		int freeSlot = allocate(environment);
		if(s.variables.size() > 1) {
			// this is the destructuring case			
			blk.append(Code.ForAll(null, freeSlot, label, Collections.EMPTY_SET), attributes(s));
			blk.append(Code.Load(null, freeSlot), attributes(s));
			blk.append(Code.Destructure(null), attributes(s));
			for(int i=s.variables.size();i>0;--i) {
				String var = s.variables.get(i-1);
				int varReg = allocate(var,environment);
				blk.append(Code.Store(null, varReg), attributes(s));
			}										
		} else {
			// easy case.
			int freeReg = allocate(s.variables.get(0),environment);
			blk.append(Code.ForAll(null, freeReg, label, Collections.EMPTY_SET), attributes(s));
		}		
		// FIXME: add a continue scope
		scopes.push(new BreakScope(label));		
		for (Stmt st : s.body) {			
			blk.append(resolve(st, environment));
		}		
		scopes.pop(); // break
		
		if(s.invariant != null) {
			String invariantLabel = Block.freshLabel();
			blk.append(Code.Assert(invariantLabel),attributes(s));
			blk.append(resolveCondition(invariantLabel, s.invariant, environment));		
			blk.append(Code.Fail("loop invariant not restored"), attributes(s));
			blk.append(Code.Label(invariantLabel));			
		}
		blk.append(Code.End(label), attributes(s));		

		return blk;
	}

	/**
	 * Translate a source-level condition into a wyil block, using a given
	 * environment mapping named variables to slots. If the condition evaluates
	 * to true, then control is transferred to the given target. Otherwise,
	 * control will fall through to the following bytecode.
	 * 
	 * @param target
	 *            --- target label to goto if condition is true.
	 * @param condition
	 *            --- source-level condition to be translated
	 * @param environment
	 *            --- mapping from variable names to to slot numbers.
	 * @return
	 */
	private Block resolveCondition(String target, Expr condition,
			 HashMap<String, Integer> environment) {
		try {
			if (condition instanceof Expr.Constant) {
				return resolveCondition(target, (Expr.Constant) condition, environment);
			} else if (condition instanceof Expr.LocalVariable) {
				return resolveCondition(target, (Expr.LocalVariable) condition, environment);
			} else if (condition instanceof Expr.ExternalAccess) {
				return resolveCondition(target, (Expr.ExternalAccess) condition, environment);
			} else if (condition instanceof Expr.BinOp) {
				return resolveCondition(target, (Expr.BinOp) condition, environment);
			} else if (condition instanceof Expr.UnOp) {
				return resolveCondition(target, (Expr.UnOp) condition, environment);
			} else if (condition instanceof Expr.Invoke) {
				return resolveCondition(target, (Expr.Invoke) condition, environment);
			} else if (condition instanceof Expr.RecordAccess) {
				return resolveCondition(target, (Expr.RecordAccess) condition, environment);
			} else if (condition instanceof Expr.RecordGen) {
				return resolveCondition(target, (Expr.RecordGen) condition, environment);
			} else if (condition instanceof Expr.TupleGen) {
				return resolveCondition(target, (Expr.TupleGen) condition, environment);
			} else if (condition instanceof Expr.ListAccess) {
				return resolveCondition(target, (Expr.ListAccess) condition, environment);
			} else if (condition instanceof Expr.Comprehension) {
				return resolveCondition(target, (Expr.Comprehension) condition, environment);
			} else {				
				syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), filename, condition);
			}
		} catch (SyntaxError se) {
			throw se;
		} catch (Exception ex) {
			internalFailure("internal failure", filename, condition, ex);
		}

		return null;
	}

	private Block resolveCondition(String target, Expr.Constant c, HashMap<String,Integer> environment) {
		Value.Bool b = (Value.Bool) c.value;
		Block blk = new Block(environment.size());
		if (b.value) {
			blk.append(Code.Goto(target));
		} else {
			// do nout
		}
		return blk;
	}

	private Block resolveCondition(String target, Expr.LocalVariable v, 
			HashMap<String, Integer> environment) throws ResolveError {
		
		Block blk = new Block(environment.size());				
		blk.append(Code.Load(null, environment.get(v.var)));
		blk.append(Code.Const(Value.V_BOOL(true)),attributes(v));
		blk.append(Code.IfGoto(null,Code.COp.EQ, target),attributes(v));			

		return blk;
	}
	
	private Block resolveCondition(String target, Expr.ExternalAccess v, 
			HashMap<String, Integer> environment) throws ResolveError {
		
		Block blk = new Block(environment.size());		
		Value val = constants.get(v.nid);
		if(val == null) {
			// indicates an external access
			Module mi = loader.loadModule(v.nid.module());
			val = mi.constant(v.nid.name()).constant();
		}								
		// Obviously, this will be evaluated one way or another.
		blk.append(Code.Const(val));
		blk.append(Code.Const(Value.V_BOOL(true)),attributes(v));
		blk.append(Code.IfGoto(null,Code.COp.EQ, target),attributes(v));			
		return blk;
	}
	
	/*
	private Block oldResolveCondition(String target, LocalVariable v, 
			HashMap<String, Integer> environment) throws ResolveError {
	
		Attributes.Alias alias = v.attribute(Attributes.Alias.class);					
		Attributes.Module mod = v.attribute(Attributes.Module.class);
		Type.Fun tf = null;
		
		if(currentFunDecl != null) {
			tf = currentFunDecl.attribute(Attributes.Fun.class).type;
		}			
		
		boolean matched=false;
		
		if (alias != null) {
			if(alias.alias != null) {							
				blk.append(resolve(alias.alias, environment));				
			} else {
				// Ok, must be a local variable
				blk.append(Code.Load(null, environment.get(v.var)));	
			}
			matched = true;
		} else if(tf != null && tf instanceof Type.Meth) {
			Type.Meth mt = (Type.Meth) tf;
			Type pt = mt.receiver();			
			if(pt instanceof Type.Process) {
				Type.Record ert = Type.effectiveRecordType(((Type.Process)pt).element());
				if(ert != null && ert.fields().containsKey(v.var)) {
					// Bingo, this is an implicit field dereference
					blk.append(Code.Load(Type.T_BOOL, environment.get("this")));	
					blk.append(Code.ProcLoad(null));
					blk.append(Code.FieldLoad(null, v.var));
					matched = true;
				} 
			}
		} else if (mod != null) {
			NameID name = new NameID(mod.module, v.var);
			Value val = constants.get(name);
			if (val == null) {
				// indicates a non-local constant definition
				Module mi = loader.loadModule(mod.module);
				val = mi.constant(v.var).constant();				
			}
			blk.append(Code.Const(val));
			matched = true;
		} 
		
		if(!matched) {
			syntaxError("unknown variable \"" + v.var + "\"",filename,v);
			return null;
		}
						
		blk.append(Code.Const(Value.V_BOOL(true)),attributes(v));
		blk.append(Code.IfGoto(null,Code.COp.EQ, target),attributes(v));			
		
		return blk;
	}
*/
	private Block resolveCondition(String target, Expr.BinOp v, HashMap<String,Integer> environment) {
		Expr.BOp bop = v.op;
		Block blk = new Block(environment.size());

		if (bop == Expr.BOp.OR) {
			blk.append(resolveCondition(target, v.lhs, environment));
			blk.append(resolveCondition(target, v.rhs, environment));
			return blk;
		} else if (bop == Expr.BOp.AND) {
			String exitLabel = Block.freshLabel();
			blk.append(resolveCondition(exitLabel, invert(v.lhs), environment));
			blk.append(resolveCondition(target, v.rhs, environment));
			blk.append(Code.Label(exitLabel));
			return blk;
		} else if (bop == Expr.BOp.TYPEEQ || bop == Expr.BOp.TYPEIMPLIES) {
			return resolveTypeCondition(target, v, environment);
		}

		Code.COp cop = OP2COP(bop,v);
		
		if (cop == Code.COp.EQ && v.lhs instanceof Expr.LocalVariable
				&& v.rhs instanceof Expr.Constant
				&& ((Expr.Constant) v.rhs).value == Value.V_NULL) {
			// this is a simple rewrite to enable type inference.
			Expr.LocalVariable lhs = (Expr.LocalVariable) v.lhs;
			if (!environment.containsKey(lhs.var)) {
				syntaxError(errorMessage(UNKNOWN_VARIABLE), filename, v.lhs);
			}
			int slot = environment.get(lhs.var);					
			blk.append(Code.IfType(null, slot, Type.T_NULL, target), attributes(v));
		} else if (cop == Code.COp.NEQ && v.lhs instanceof Expr.LocalVariable
				&& v.rhs instanceof Expr.Constant
				&& ((Expr.Constant) v.rhs).value == Value.V_NULL) {			
			// this is a simple rewrite to enable type inference.
			String exitLabel = Block.freshLabel();
			Expr.LocalVariable lhs = (Expr.LocalVariable) v.lhs;
			if (!environment.containsKey(lhs.var)) {
				syntaxError(errorMessage(UNKNOWN_VARIABLE), filename, v.lhs);
			}
			int slot = environment.get(lhs.var);						
			blk.append(Code.IfType(null, slot, Type.T_NULL, exitLabel), attributes(v));
			blk.append(Code.Goto(target));
			blk.append(Code.Label(exitLabel));
		} else {
			blk.append(resolve(v.lhs, environment));			
			blk.append(resolve(v.rhs, environment));
			blk.append(Code.IfGoto(null, cop, target), attributes(v));
		}
		return blk;
	}

	private Block resolveTypeCondition(String target, Expr.BinOp v, HashMap<String,Integer> environment) {
		Block blk;
		int slot;
		
		if (v.lhs instanceof Expr.LocalVariable) {
			Expr.LocalVariable lhs = (Expr.LocalVariable) v.lhs;
			if (!environment.containsKey(lhs.var)) {
				syntaxError(errorMessage(UNKNOWN_VARIABLE), filename, v.lhs);
			}
			slot = environment.get(lhs.var);
			blk = new Block(environment.size());
		} else {
			blk = resolve(v.lhs, environment);
			slot = -1;
		}

		Pair<Type,Block> rhs_t = resolve(((Expr.Type) v.rhs).type);
		// TODO: fix type constraints
		blk.append(Code.IfType(null, slot, rhs_t.first(), target),
				attributes(v));
		return blk;
	}

	private Block resolveCondition(String target, Expr.UnOp v, HashMap<String,Integer> environment) {
		Expr.UOp uop = v.op;
		switch (uop) {
		case NOT:
			String label = Block.freshLabel();
			Block blk = resolveCondition(label, v.mhs, environment);
			blk.append(Code.Goto(target));
			blk.append(Code.Label(label));
			return blk;
		}
		syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), filename, v);
		return null;
	}

	private Block resolveCondition(String target, Expr.ListAccess v, HashMap<String,Integer> environment) {
		Block blk = resolve(v, environment);
		blk.append(Code.Const(Value.V_BOOL(true)),attributes(v));
		blk.append(Code.IfGoto(Type.T_BOOL, Code.COp.EQ, target),attributes(v));
		return blk;
	}

	private Block resolveCondition(String target, Expr.RecordAccess v, HashMap<String,Integer> environment) {
		Block blk = resolve(v, environment);		
		blk.append(Code.Const(Value.V_BOOL(true)),attributes(v));
		blk.append(Code.IfGoto(Type.T_BOOL, Code.COp.EQ, target),attributes(v));		
		return blk;
	}

	private Block resolveCondition(String target, Expr.Invoke v, HashMap<String,Integer> environment) throws ResolveError {
		Block blk = resolve((Expr) v, environment);	
		blk.append(Code.Const(Value.V_BOOL(true)),attributes(v));
		blk.append(Code.IfGoto(Type.T_BOOL, Code.COp.EQ, target),attributes(v));
		return blk;
	}

	private Block resolveCondition(String target, Expr.Comprehension e,  
			HashMap<String,Integer> environment) {
		
		if (e.cop != Expr.COp.NONE && e.cop != Expr.COp.SOME) {
			syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), filename, e);
		}
					
		// Ok, non-boolean case.				
		Block blk = new Block(environment.size());
		ArrayList<Pair<Integer,Integer>> slots = new ArrayList();		
		
		for (Pair<String, Expr> src : e.sources) {
			int srcSlot;
			int varSlot = allocate(src.first(),environment); 
			
			if(src.second() instanceof Expr.LocalVariable) {
				// this is a little optimisation to produce slightly better
				// code.
				Expr.LocalVariable v = (Expr.LocalVariable) src.second();
				if(environment.containsKey(v.var)) {					
					srcSlot = environment.get(v.var);
				} else {					
					// fall-back plan ...
					blk.append(resolve(src.second(), environment));
					srcSlot = allocate(environment);
					blk.append(Code.Store(null, srcSlot),attributes(e));	
				}
			} else {
				blk.append(resolve(src.second(), environment));
				srcSlot = allocate(environment);
				blk.append(Code.Store(null, srcSlot),attributes(e));	
			}			
			slots.add(new Pair(varSlot,srcSlot));											
		}
				
		ArrayList<String> labels = new ArrayList<String>();
		String loopLabel = Block.freshLabel();
		
		for (Pair<Integer, Integer> p : slots) {
			String lab = loopLabel + "$" + p.first();
			blk.append(Code.Load(null, p.second()), attributes(e));			
			blk.append(Code
					.ForAll(null, p.first(), lab, Collections.EMPTY_LIST),
					attributes(e));
			labels.add(lab);
		}
								
		if (e.cop == Expr.COp.NONE) {
			String exitLabel = Block.freshLabel();
			blk.append(resolveCondition(exitLabel, e.condition, 
					environment));
			for (int i = (labels.size() - 1); i >= 0; --i) {				
				blk.append(Code.End(labels.get(i)));
			}
			blk.append(Code.Goto(target));
			blk.append(Code.Label(exitLabel));
		} else { // SOME			
			blk.append(resolveCondition(target, e.condition, 
					environment));
			for (int i = (labels.size() - 1); i >= 0; --i) {
				blk.append(Code.End(labels.get(i)));
			}
		} // ALL, LONE and ONE will be harder					
		
		return blk;
	}

	/**
	 * Translate a source-level expression into a wyil block, using a given
	 * environment mapping named variables to slots. The result of the
	 * expression remains on the wyil stack.
	 * 
	 * @param expression
	 *            --- source-level expression to be translated
	 * @param environment
	 *            --- mapping from variable names to to slot numbers.
	 * @return
	 */
	private Block resolve(Expr expression, HashMap<String,Integer> environment) {
		try {
			if (expression instanceof Expr.Constant) {
				return resolve((Expr.Constant) expression, environment);
			} else if (expression instanceof Expr.LocalVariable) {
				return resolve((Expr.LocalVariable) expression, environment);
			} else if (expression instanceof Expr.ExternalAccess) {
				return resolve((Expr.ExternalAccess) expression, environment);
			} else if (expression instanceof Expr.NaryOp) {
				return resolve((Expr.NaryOp) expression, environment);
			} else if (expression instanceof Expr.BinOp) {
				return resolve((Expr.BinOp) expression, environment);
			} else if (expression instanceof Expr.Convert) {
				return resolve((Expr.Convert) expression, environment);
			} else if (expression instanceof Expr.ListAccess) {
				return resolve((Expr.ListAccess) expression, environment);
			} else if (expression instanceof Expr.UnOp) {
				return resolve((Expr.UnOp) expression, environment);
			} else if (expression instanceof Expr.Invoke) {
				return resolve((Expr.Invoke) expression, true, environment);
			} else if (expression instanceof Expr.Comprehension) {
				return resolve((Expr.Comprehension) expression, environment);
			} else if (expression instanceof Expr.RecordAccess) {
				return resolve((Expr.RecordAccess) expression, environment);
			} else if (expression instanceof Expr.RecordGen) {
				return resolve((Expr.RecordGen) expression, environment);
			} else if (expression instanceof Expr.TupleGen) {
				return resolve((Expr.TupleGen) expression, environment);
			} else if (expression instanceof Expr.DictionaryGen) {
				return resolve((Expr.DictionaryGen) expression, environment);
			} else if (expression instanceof Expr.Function) {
				return resolve((Expr.Function) expression, environment);
			} else {
				// should be dead-code
				internalFailure("unknown expression encountered: "
						+ expression.getClass().getName() + " (" + expression
						+ ")", filename, expression);
			}
		} catch (ResolveError rex) {
			syntaxError(rex.getMessage(), filename, expression, rex);
		} catch (SyntaxError se) {
			throw se;
		} catch (Exception ex) {
			internalFailure("internal failure", filename, expression, ex);
		}

		return null;
	}

	private Block resolve(Expr.Invoke s, boolean retval, HashMap<String,Integer> environment) throws ResolveError {
		List<Expr> args = s.arguments;
		Block blk = new Block(environment.size());
		Type[] paramTypes = new Type[args.size()]; 
		
		boolean receiverIsThis = s.receiver != null && s.receiver instanceof Expr.LocalVariable && ((Expr.LocalVariable)s.receiver).var.equals("this");
		
		Attributes.Module modInfo = s.attribute(Attributes.Module.class);

		/**
		 * An indirect variable invoke represents an invoke statement on a local
		 * variable.
		 */
		boolean variableIndirectInvoke = environment.containsKey(s.name);

		/**
		 * A direct invoke indicates no receiver was provided, and there was a
		 * matching external symbol.
		 */
		boolean directInvoke = !variableIndirectInvoke && s.receiver == null && modInfo != null;		
		
		/**
		 * A method invoke indicates the receiver was this, and there was a
		 * matching external symbol.
		 */
		boolean methodInvoke = !variableIndirectInvoke && receiverIsThis && modInfo != null;
		
		/**
		 * An field indirect invoke indicates an invoke statement on a value
		 * coming out of a field.
		 */
		boolean fieldIndirectInvoke = !variableIndirectInvoke && s.receiver != null && modInfo == null;

		/**
		 * A direct send indicates a message send to a matching external symbol.
		 */
		boolean directSend = !variableIndirectInvoke && s.receiver != null
				&& !receiverIsThis && modInfo != null;
											
		if(variableIndirectInvoke) {
			blk.append(Code.Load(null, environment.get(s.name)),attributes(s));
		} 
		
		if (s.receiver != null) {			
			blk.append(resolve(s.receiver, environment));
		}

		if(fieldIndirectInvoke) {
			blk.append(Code.FieldLoad(null, s.name),attributes(s));
		}
		
		int i = 0;
		for (Expr e : args) {
			blk.append(resolve(e, environment));
			paramTypes[i++] = Type.T_ANY;
		}	
					
		if(variableIndirectInvoke) {			
			if(s.receiver != null) {
				Type.Method mt = checkType(Type.Method(null, Type.T_VOID, Type.T_VOID, paramTypes),Type.Method.class,s);
				blk.append(Code.IndirectSend(mt,s.synchronous, retval),attributes(s));
			} else {
				Type.Function ft = checkType(Type.Function(Type.T_VOID, Type.T_VOID, paramTypes),Type.Function.class,s);
				blk.append(Code.IndirectInvoke(ft, retval),attributes(s));
			}
		} else if(fieldIndirectInvoke) {
			Type.Function ft = checkType(Type.Function(Type.T_VOID, Type.T_VOID, paramTypes),Type.Function.class,s);
			blk.append(Code.IndirectInvoke(ft, retval),attributes(s));
		} else if(directInvoke || methodInvoke) {
			NameID name = new NameID(modInfo.module, s.name);
			if(receiverIsThis) {
				Type.Method mt = checkType(
						Type.Method(null, Type.T_VOID, Type.T_VOID, paramTypes),
						Type.Method.class, s);
				blk.append(Code.Invoke(mt, name, retval), attributes(s));
			} else {
				Type.Function ft = checkType(
						Type.Function(Type.T_VOID, Type.T_VOID, paramTypes),
						Type.Function.class, s);
				blk.append(Code.Invoke(ft, name, retval), attributes(s));
			}
		} else if(directSend) {						
			NameID name = new NameID(modInfo.module, s.name);
			Type.Method mt = checkType(
					Type.Method(null, Type.T_VOID, Type.T_VOID, paramTypes),
					Type.Method.class, s);
			blk.append(Code.Send(mt, name, s.synchronous, retval),
					attributes(s));
		} else {
			syntaxError(errorMessage(UNKNOWN_FUNCTION_OR_METHOD), filename, s);
		}
		
		return blk;
	}

	private Block resolve(Expr.Constant c, HashMap<String,Integer> environment) {
		Block blk = new Block(environment.size());
		blk.append(Code.Const(c.value), attributes(c));		
		return blk;
	}

	private Block resolve(Expr.Function s, HashMap<String,Integer> environment) {
		Attributes.Module modInfo = s.attribute(Attributes.Module.class);		
		NameID name = new NameID(modInfo.module, s.name);	
		Type.Function tf = null;
		if(s.paramTypes != null) {
			// in this case, the user has provided explicit type information.
			ArrayList<Type> paramTypes = new ArrayList<Type>();
			for(UnresolvedType pt : s.paramTypes) {
				Pair<Type,Block> p = resolve(pt);
				// TODO: fix parameter constraints
				paramTypes.add(p.first());
			}
			tf = checkType(Type.Function(Type.T_ANY, Type.T_VOID, paramTypes),
					Type.Function.class, s);
		}
		Block blk = new Block(environment.size());
		blk.append(Code.Const(Value.V_FUN(name, tf)),
				attributes(s));
		return blk;
	}
	
	private Block resolve(Expr.ExternalAccess v, HashMap<String,Integer> environment) throws ResolveError {						
		Value val = constants.get(v.nid);		
		if(val == null) {
			// indicates an external access
			Module mi = loader.loadModule(v.nid.module());
			val = mi.constant(v.nid.name()).constant();
		}
		Block blk = new Block(environment.size());
		blk.append(Code.Const(val),attributes(v));
		return blk;
	}
	
	private Block resolve(Expr.LocalVariable v, HashMap<String,Integer> environment) throws ResolveError {
		if(environment.containsKey(v.var)) {
			Block blk = new Block(environment.size());						
			blk.append(Code.Load(null, environment.get(v.var)), attributes(v));					
			return blk;
		} else {
			syntaxError(errorMessage(VARIABLE_POSSIBLY_UNITIALISED), filename,
					v);
		}
		
		// Third, see if it's a constant
		Attributes.Module mod = v.attribute(Attributes.Module.class);
		if (mod != null) {
			NameID name = new NameID(mod.module, v.var);
			Value val = constants.get(name);
			if (val == null) {
				// indicates a non-local constant definition
				Module mi = loader.loadModule(mod.module);
				val = mi.constant(v.var).constant();
			}
			Block blk = new Block(environment.size());
			blk.append(Code.Const(val),attributes(v));
			return blk;
		}
		
		// must be an error
		syntaxError("unknown variable \"" + v.var + "\"",filename,v);
		return null;
	}

	private Block resolve(Expr.UnOp v, HashMap<String,Integer> environment) {
		Block blk = resolve(v.mhs,  environment);	
		switch (v.op) {
		case NEG:
			blk.append(Code.Negate(null), attributes(v));
			break;
		case INVERT:
			blk.append(Code.Invert(null), attributes(v));
			break;
		case NOT:
			String falseLabel = Block.freshLabel();
			String exitLabel = Block.freshLabel();
			blk = resolveCondition(falseLabel, v.mhs, environment);
			blk.append(Code.Const(Value.V_BOOL(true)), attributes(v));
			blk.append(Code.Goto(exitLabel));
			blk.append(Code.Label(falseLabel));
			blk.append(Code.Const(Value.V_BOOL(false)), attributes(v));
			blk.append(Code.Label(exitLabel));
			break;
		case LENGTHOF:
			blk.append(Code.ListLength(null), attributes(v));
			break;
		case PROCESSACCESS:
			blk.append(Code.ProcLoad(null), attributes(v));
			break;			
		case PROCESSSPAWN:
			blk.append(Code.Spawn(null), attributes(v));
			break;			
		default:
			// should be dead-code
			internalFailure("unexpected unary operator encountered", filename, v);
			return null;
		}
		return blk;
	}

	private Block resolve(Expr.ListAccess v, HashMap<String,Integer> environment) {
		Block blk = new Block(environment.size());
		blk.append(resolve(v.src, environment));
		blk.append(resolve(v.index, environment));
		blk.append(Code.ListLoad(null),attributes(v));
		return blk;
	}

	private Block resolve(Expr.Convert v, HashMap<String,Integer> environment) {
		Block blk = new Block(environment.size());
		blk.append(resolve(v.expr, environment));		
		Pair<Type,Block> p = resolve(v.type);
		// TODO: include constraints
		blk.append(Code.Convert(null,p.first()),attributes(v));
		return blk;
	}
	
	private Block resolve(Expr.BinOp v, HashMap<String,Integer> environment) {

		// could probably use a range test for this somehow
		if (v.op == Expr.BOp.EQ || v.op == Expr.BOp.NEQ || v.op == Expr.BOp.LT
				|| v.op == Expr.BOp.LTEQ || v.op == Expr.BOp.GT || v.op == Expr.BOp.GTEQ
				|| v.op == Expr.BOp.SUBSET || v.op == Expr.BOp.SUBSETEQ
				|| v.op == Expr.BOp.ELEMENTOF || v.op == Expr.BOp.AND || v.op == Expr.BOp.OR) {
			String trueLabel = Block.freshLabel();
			String exitLabel = Block.freshLabel();
			Block blk = resolveCondition(trueLabel, v, environment);
			blk.append(Code.Const(Value.V_BOOL(false)), attributes(v));			
			blk.append(Code.Goto(exitLabel));
			blk.append(Code.Label(trueLabel));
			blk.append(Code.Const(Value.V_BOOL(true)), attributes(v));				
			blk.append(Code.Label(exitLabel));			
			return blk;
		}

		Expr.BOp bop = v.op;
		Block blk = new Block(environment.size());
		blk.append(resolve(v.lhs, environment));
		blk.append(resolve(v.rhs, environment));

		if(bop == Expr.BOp.UNION) {
			blk.append(Code.SetUnion(null,Code.OpDir.UNIFORM),attributes(v));			
			return blk;			
		} else if(bop == Expr.BOp.INTERSECTION) {
			blk.append(Code.SetIntersect(null,Code.OpDir.UNIFORM),attributes(v));
			return blk;			
		} else {
			blk.append(Code.BinOp(null, OP2BOP(bop,v)),attributes(v));			
			return blk;
		}		
	}

	private Block resolve(Expr.NaryOp v, HashMap<String,Integer> environment) {
		Block blk = new Block(environment.size());
		if (v.nop == Expr.NOp.SUBLIST) {
			if (v.arguments.size() != 3) {
				// this should be dead-code
				internalFailure("incorrect number of arguments", filename, v);
			}
			blk.append(resolve(v.arguments.get(0), environment));
			blk.append(resolve(v.arguments.get(1), environment));
			blk.append(resolve(v.arguments.get(2), environment));
			blk.append(Code.SubList(null),attributes(v));
			return blk;
		} else {			
			int nargs = 0;
			for (Expr e : v.arguments) {				
				nargs++;
				blk.append(resolve(e, environment));
			}

			if (v.nop == Expr.NOp.LISTGEN) {
				blk.append(Code.NewList(null,nargs),attributes(v));
			} else {
				blk.append(Code.NewSet(null,nargs),attributes(v));
			}
			return blk;
		}
	}
	
	private Block resolve(Expr.Comprehension e, HashMap<String,Integer> environment) {

		// First, check for boolean cases which are handled mostly by
		// resolveCondition.
		if (e.cop == Expr.COp.SOME || e.cop == Expr.COp.NONE) {
			String trueLabel = Block.freshLabel();
			String exitLabel = Block.freshLabel();
			int freeSlot = allocate(environment);
			Block blk = resolveCondition(trueLabel, e, environment);					
			blk.append(Code.Const(Value.V_BOOL(false)), attributes(e));
			blk.append(Code.Store(null,freeSlot),attributes(e));			
			blk.append(Code.Goto(exitLabel));
			blk.append(Code.Label(trueLabel));
			blk.append(Code.Const(Value.V_BOOL(true)), attributes(e));
			blk.append(Code.Store(null,freeSlot),attributes(e));
			blk.append(Code.Label(exitLabel));
			blk.append(Code.Load(null,freeSlot),attributes(e));
			return blk;
		}

		// Ok, non-boolean case.				
		Block blk = new Block(environment.size());
		ArrayList<Pair<Integer,Integer>> slots = new ArrayList();		
		
		for (Pair<String, Expr> src : e.sources) {
			int srcSlot;
			int varSlot = allocate(src.first(),environment); 
			
			if(src.second() instanceof Expr.LocalVariable) {
				// this is a little optimisation to produce slightly better
				// code.
				Expr.LocalVariable v = (Expr.LocalVariable) src.second();
				if(environment.containsKey(v.var)) {
					srcSlot = environment.get(v.var);
				} else {
					// fall-back plan ...
					blk.append(resolve(src.second(), environment));
					srcSlot = allocate(environment);				
					blk.append(Code.Store(null, srcSlot),attributes(e));	
				}
			} else {
				blk.append(resolve(src.second(), environment));
				srcSlot = allocate(environment);
				blk.append(Code.Store(null, srcSlot),attributes(e));	
			}			
			slots.add(new Pair(varSlot,srcSlot));											
		}
		
		int resultSlot = allocate(environment);
		
		if (e.cop == Expr.COp.LISTCOMP) {
			blk.append(Code.NewList(null,0), attributes(e));
			blk.append(Code.Store(null,resultSlot),attributes(e));
		} else {
			blk.append(Code.NewSet(null,0), attributes(e));
			blk.append(Code.Store(null,resultSlot),attributes(e));			
		}
		
		// At this point, it would be good to determine an appropriate loop
		// invariant for a set comprehension. This is easy enough in the case of
		// a single variable comprehension, but actually rather difficult for a
		// multi-variable comprehension.
		//
		// For example, consider <code>{x+y | x in xs, y in ys, x<0 && y<0}</code>
		// 
		// What is an appropriate loop invariant here?
		
		String continueLabel = Block.freshLabel();
		ArrayList<String> labels = new ArrayList<String>();
		String loopLabel = Block.freshLabel();
		
		for (Pair<Integer, Integer> p : slots) {
			String target = loopLabel + "$" + p.first();
			blk.append(Code.Load(null, p.second()), attributes(e));
			blk.append(Code
					.ForAll(null, p.first(), target, Collections.EMPTY_LIST),
					attributes(e));
			labels.add(target);
		}
		
		if (e.condition != null) {
			blk.append(resolveCondition(continueLabel, invert(e.condition),
					environment));
		}
		
		blk.append(Code.Load(null,resultSlot),attributes(e));
		blk.append(resolve(e.value, environment));
		blk.append(Code.SetUnion(null, Code.OpDir.LEFT),attributes(e));
		blk.append(Code.Store(null,resultSlot),attributes(e));
			
		if(e.condition != null) {
			blk.append(Code.Label(continueLabel));			
		} 

		for (int i = (labels.size() - 1); i >= 0; --i) {
			blk.append(Code.End(labels.get(i)));
		}

		blk.append(Code.Load(null,resultSlot),attributes(e));
		
		return blk;
	}

	private Block resolve(Expr.RecordGen sg, HashMap<String,Integer> environment) {
		Block blk = new Block(environment.size());
		HashMap<String, Type> fields = new HashMap<String, Type>();
		ArrayList<String> keys = new ArrayList<String>(sg.fields.keySet());
		Collections.sort(keys);
		for (String key : keys) {
			fields.put(key, Type.T_ANY);
			blk.append(resolve(sg.fields.get(key), environment));
		}
		Type.Record rt = checkType(Type.Record(false,fields),Type.Record.class,sg);
		blk.append(Code.NewRecord(rt), attributes(sg));
		return blk;
	}

	private Block resolve(Expr.TupleGen sg, HashMap<String,Integer> environment) {		
		Block blk = new Block(environment.size());		
		for (Expr e : sg.fields) {									
			blk.append(resolve(e, environment));
		}
		// FIXME: to be updated to proper tuple
		blk.append(Code.NewTuple(null,sg.fields.size()),attributes(sg));
		return blk;		
	}

	private Block resolve(Expr.DictionaryGen sg, HashMap<String,Integer> environment) {		
		Block blk = new Block(environment.size());		
		for (Pair<Expr,Expr> e : sg.pairs) {			
			blk.append(resolve(e.first(), environment));
			blk.append(resolve(e.second(), environment));
		}
		blk.append(Code.NewDict(null,sg.pairs.size()),attributes(sg));
		return blk;
	}
	
	private Block resolve(Expr.RecordAccess sg, HashMap<String,Integer> environment) {
		Block lhs = resolve(sg.lhs, environment);		
		lhs.append(Code.FieldLoad(null,sg.name), attributes(sg));
		return lhs;
	}
	
	private static int allocate(HashMap<String,Integer> environment) {
		return allocate("$" + environment.size(),environment);
	}
	
	private static int allocate(String var, HashMap<String,Integer> environment) {
		// this method is a bit of a hack
		Integer r = environment.get(var);
		if(r == null) {
			int slot = environment.size();
			environment.put(var, slot);
			return slot;
		} else {
			return r;
		}
	}	
		
	private void resolve(UnresolvedType t) {
		HashMap<NameID,Type> cache = new HashMap<NameID,Type>();
		Pair<Type,Block> pt = expandType(t,filename,cache);
		t.attributes().add(new Attributes.Type(pt.first(), pt.second()));
	}
	
	private static Expr invert(Expr e) {
		if (e instanceof Expr.BinOp) {
			Expr.BinOp bop = (Expr.BinOp) e;
			switch (bop.op) {
			case AND:
				return new Expr.BinOp(Expr.BOp.OR, invert(bop.lhs), invert(bop.rhs), attributes(e));
			case OR:
				return new Expr.BinOp(Expr.BOp.AND, invert(bop.lhs), invert(bop.rhs), attributes(e));
			case EQ:
				return new Expr.BinOp(Expr.BOp.NEQ, bop.lhs, bop.rhs, attributes(e));
			case NEQ:
				return new Expr.BinOp(Expr.BOp.EQ, bop.lhs, bop.rhs, attributes(e));
			case LT:
				return new Expr.BinOp(Expr.BOp.GTEQ, bop.lhs, bop.rhs, attributes(e));
			case LTEQ:
				return new Expr.BinOp(Expr.BOp.GT, bop.lhs, bop.rhs, attributes(e));
			case GT:
				return new Expr.BinOp(Expr.BOp.LTEQ, bop.lhs, bop.rhs, attributes(e));
			case GTEQ:
				return new Expr.BinOp(Expr.BOp.LT, bop.lhs, bop.rhs, attributes(e));
			}
		} else if (e instanceof Expr.UnOp) {
			Expr.UnOp uop = (Expr.UnOp) e;
			switch (uop.op) {
			case NOT:
				return uop.mhs;
			}
		}
		return new Expr.UnOp(Expr.UOp.NOT, e);
	}

	private Code.BOp OP2BOP(Expr.BOp bop, SyntacticElement elem) {
		switch (bop) {
		case ADD:
			return Code.BOp.ADD;
		case SUB:
			return Code.BOp.SUB;		
		case MUL:
			return Code.BOp.MUL;
		case DIV:
			return Code.BOp.DIV;
		case REM:
			return Code.BOp.REM;
		case RANGE:
			return Code.BOp.RANGE;
		case BITWISEAND:
			return Code.BOp.BITWISEAND;
		case BITWISEOR:
			return Code.BOp.BITWISEOR;
		case BITWISEXOR:
			return Code.BOp.BITWISEXOR;
		case LEFTSHIFT:
			return Code.BOp.LEFTSHIFT;
		case RIGHTSHIFT:
			return Code.BOp.RIGHTSHIFT;
		}
		syntaxError(errorMessage(INVALID_BINARY_EXPRESSION), filename, elem);
		return null;
	}

	private Code.COp OP2COP(Expr.BOp bop, SyntacticElement elem) {
		switch (bop) {
		case EQ:
			return Code.COp.EQ;
		case NEQ:
			return Code.COp.NEQ;
		case LT:
			return Code.COp.LT;
		case LTEQ:
			return Code.COp.LTEQ;
		case GT:
			return Code.COp.GT;
		case GTEQ:
			return Code.COp.GTEQ;
		case SUBSET:
			return Code.COp.SUBSET;
		case SUBSETEQ:
			return Code.COp.SUBSETEQ;
		case ELEMENTOF:
			return Code.COp.ELEMOF;
		}
		syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), filename, elem);
		return null;
	}


	/**
	 * The shiftBlock method takes a block and shifts every slot a given amount
	 * to the right. The number of inputs remains the same. This method is used 
	 * 
	 * @param amount
	 * @param blk
	 * @return
	 */
	private static Block shiftBlock(int amount, Block blk) {
		HashMap<Integer,Integer> binding = new HashMap<Integer,Integer>();
		for(int i=0;i!=blk.numSlots();++i) {
			binding.put(i,i+amount);
		}
		Block nblock = new Block(blk.numInputs());
		for(Block.Entry e : blk) {
			Code code = e.code.remap(binding);
			nblock.append(code,e.attributes());
		}
		return nblock.relabel();
	}
	
	/**
	 * The chainBlock method takes a block and replaces every fail statement
	 * with a goto to a given label. This is useful for handling constraints in
	 * union types, since if the constraint is not met that doesn't mean its
	 * game over.
	 * 
	 * @param target
	 * @param blk
	 * @return
	 */
	private static Block chainBlock(String target, Block blk) {	
		Block nblock = new Block(blk.numInputs());
		for (Block.Entry e : blk) {
			if (e.code instanceof Code.Fail) {
				nblock.append(Code.Goto(target), e.attributes());
			} else {
				nblock.append(e.code, e.attributes());
			}
		}
		return nblock.relabel();
	}
	
	/**
	 * The attributes method extracts those attributes of relevance to wyil, and
	 * discards those which are only used for the wyc front end.
	 * 
	 * @param elem
	 * @return
	 */
	private static Collection<Attribute> attributes(SyntacticElement elem) {
		ArrayList<Attribute> attrs = new ArrayList<Attribute>();
		attrs.add(elem.attribute(Attribute.Source.class));
		return attrs;
	}

	private <T extends Type> T checkType(Type t, Class<T> clazz,
			SyntacticElement elem) {		
		if (clazz.isInstance(t)) {
			return (T) t;
		} else {
			// TODO: need a better error message here.
			String errMsg = errorMessage(SUBTYPE_ERROR,clazz.getName().replace('$',' '),t);
			syntaxError(errMsg, filename, elem);
			return null;
		}
	}
	
	private <T extends Scope> T findEnclosingScope(Class<T> c) {
		for(int i=scopes.size()-1;i>=0;--i) {
			Scope s = scopes.get(i);
			if(c.isInstance(s)) {
				return (T) s;
			}
		}
		return null;
	}	
	
	private abstract class Scope {}
	
	private class BreakScope extends Scope {
		public String label;
		public BreakScope(String l) { label = l; }
	}

	private class ContinueScope extends Scope {
		public String label;
		public ContinueScope(String l) { label = l; }
	}
}