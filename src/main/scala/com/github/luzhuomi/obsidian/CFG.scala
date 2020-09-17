package com.github.luzhuomi.obsidian

import cats._
import cats.implicits._
import cats.data.StateT

import com.github.luzhuomi.scalangj.Syntax._
import com.github.luzhuomi.obsidian.ASTUtils._

/*
 Control Flow Graph construction
 */

object CFG {

  type CFG = Map[NodeId, Node]

  /**
    * Node in a control flow graph
    *
    * @param stmts the list of statments contained in this node
    * @param lVars variables appearing on the left hand side of assignment statement
    * @param rVars variables appearing on the right hand side of assignment statement
    * @param localDecls locally declared variables
    * @param preds predecessor nodes
    * @param succs successor nodes
    * @param nodeType node type, assignments, loop, switch, if-else, try-catch or throw
    */
  case class Node(
      stmts: List[BlockStmt],
      lVars: List[Ident],
      rVars: List[Ident],
      localDecls: List[Ident],
      preds: List[NodeId],
      succs: List[NodeId],
      nodeType: NodeType
  )

  type NodeId = Ident

  sealed trait NodeType

  case object AssignmentNode extends NodeType
  case object LoopNode extends NodeType
  case object SwitchNode extends NodeType
  case object IfElseNode extends NodeType
  case object TryCatchNode extends NodeType
  case object ThrowNode extends NodeType

  case class StateInfo(
      currId: Int,
      cfg: CFG,
      currPreds: List[NodeId],
      continuable: Boolean,
      contNodes: List[
        NodeId
      ], // it seems some of these are needed to support duff's device, which is only doable in C.
      breakNodes: List[NodeId],
      caseNodes: List[CaseExp],
      formalArgs: List[Ident],
      fallThroughCases: List[Exp]
  )

  sealed trait CaseExp
  case class DefaultCase(wrapperId: NodeId, rhs: NodeId)
  case class ExpCase(
      condExp: Exp,
      fallThrough: List[Exp],
      wrapperId: NodeId,
      rhs: NodeId
  )

  val labPref = "myLabel"
  val initStateInfo = StateInfo(
    0,
    Map[NodeId, Node](),
    List(),
    false,
    List(),
    List(),
    List(),
    List(),
    List()
  )

  sealed trait CFGResult[+A]
  case class CFGError(msg: String) extends CFGResult[Nothing]
  case class CFGOk[A](result: A) extends CFGResult[A]

  implicit def cfgResultFunctor: Functor[CFGResult] = new Functor[CFGResult] {
    override def map[A,B](fa:CFGResult[A])(f:A=>B): CFGResult[B] = fa match {
      case CFGError(s) => CFGError(s)
      case CFGOk(a) => CFGOk(f(a))
    }
  } 

  /*

  implicit def cfgResultApplicative: Applicative[CFGResult] = new Applicative[CFGResult] {
    override def ap[A,B](ff:CFGResult[A=>B])(fa:CFGResult[A]) : CFGResult[B] = ff match {
      case CFGOk(f) => fa match {
        case CFGOk(a) => CFGOk(f(a))
        case CFGError(s) => CFGError(s)
      }
      case CFGError(s) => CFGError(s)
    }

    override def pure[A](a:A):CFGResult[A] = CFGOk(a)
  }

  implicit def cfgResultMonad(implicit app: Applicative[CFGResult]) =
    new Monad[CFGResult] {
      // Define flatMap using Option's flatten method
      override def flatMap[A, B](
          fa: CFGResult[A]
      )(f: A => CFGResult[B]): CFGResult[B] = fa match {
        case CFGOk(a) => f(a)
        case CFGError(s) => CFGError(s)
      }
      override def pure[A](a: A): CFGResult[A] = app.pure(a)

      @annotation.tailrec
      def tailRecM[A, B](
          init: A
      )(fn: A => CFGResult[Either[A, B]]): CFGResult[B] =
        fn(init) match {
          case CFGError(msg)   => CFGError(msg)
          case CFGOk(Right(b)) => CFGOk(b)
          case CFGOk(Left(a))  => tailRecM(a)(fn)
        }
    }
    */


  implicit def cfgResultApplicative: ApplicativeError[CFGResult,String] = new ApplicativeError[CFGResult,String] {
    override def ap[A,B](ff:CFGResult[A=>B])(fa:CFGResult[A]) : CFGResult[B] = ff match {
      case CFGOk(f) => fa match {
        case CFGOk(a) => CFGOk(f(a))
        case CFGError(s) => CFGError(s)
      }
      case CFGError(s) => CFGError(s)
    }

    override def pure[A](a:A):CFGResult[A] = CFGOk(a)
    override def raiseError[A](e: String): CFGResult[A] = CFGError(e)
    override def handleErrorWith[A](fa: CFGResult[A])(f: String => CFGResult[A]): CFGResult[A] = fa match {
      case CFGError(s) => f(s)
      case CFGOk(a) => CFGOk(a)
    }
  }

  implicit def cfgResultMonadError(implicit app: ApplicativeError[CFGResult, String]) : MonadError[CFGResult, String] = new MonadError[CFGResult, String] {
    override def raiseError[A](e: String): CFGResult[A] = app.raiseError(e)
    override def handleErrorWith[A](fa: CFGResult[A])(f: String => CFGResult[A]): CFGResult[A] = app.handleErrorWith(fa)(f)
    override def flatMap[A, B](
          fa: CFGResult[A]
      )(f: A => CFGResult[B]): CFGResult[B] = fa match {
        case CFGOk(a) => f(a)
        case CFGError(s) => CFGError(s)
      }
      override def pure[A](a: A): CFGResult[A] = app.pure(a)

      @annotation.tailrec
      def tailRecM[A, B](
          init: A
      )(fn: A => CFGResult[Either[A, B]]): CFGResult[B] =
        fn(init) match {
          case CFGError(msg)   => CFGError(msg)
          case CFGOk(Right(b)) => CFGOk(b)
          case CFGOk(Left(a))  => tailRecM(a)(fn)
        }
    }
  
  
  /*
  def runCFG(methodDecl: MethodDecl): CFGResult[(Unit, StateInfo)] = {
    buildCFG.run(methodDecl).value
  }
  */

  type State[S, A] = StateT[CFGResult, S, A]
  type SIState[A] = State[StateInfo,A]

  trait CFGClass[A] {
    def buildCFG(a: A)(implicit m:MonadError[SIState,String]): State[StateInfo, Unit]
  }

  object ops {
    def buildCFG[A](
        a: A
    )(implicit aCFGCl: CFGClass[A]): State[StateInfo, Unit] = {
      aCFGCl.buildCFG(a)
    }
  }
  

  def get:State[StateInfo,StateInfo] = StateT { stateInfo => 
    CFGOk((stateInfo, stateInfo))
  }

  def put(st:StateInfo):State[StateInfo,Unit] = StateT { _ =>
    CFGOk((st, ()))
  }

  def idFromVarDeclId(vdid:VarDeclId):Ident = vdid match {
    case VarId(id) => id
    case VarDeclArray(vid) => idFromVarDeclId(vid)
  }

  implicit def methodCFGInstance: CFGClass[MethodDecl] = new CFGClass[MethodDecl] {
    override def buildCFG(a: MethodDecl)(implicit m:MonadError[SIState,String]): State[StateInfo, Unit] = a match {
      case MethodDecl(modifiers, type_params, return_ty, fname, formal_params, ex_types, exp, body) => for 
      { fargs <- m.pure(formal_params.map(fp => idFromVarDeclId(fp.var_decl_id)));
        _     <- ops.buildCFG(body);
        st    <- get;
        st1   <- m.pure(st); // TODO:we skip insertGoto and insertPhantom (double check)
        st2   <- m.pure(st.copy( cfg = formalArgsAsDecls(fargs,st1.cfg),
                                 formalArgs = fargs));
        _     <- put(st2)
      } yield ()
    } 
  }
  implicit def bodyCFGInstance: CFGClass[MethodBody] = new CFGClass[MethodBody] { 
    override def buildCFG(a: MethodBody)(implicit m:MonadError[SIState,String]): State[StateInfo, Unit] = a match {
      case MethodBody( None ) => m.pure(())
      case MethodBody( Some(blk)) => ops.buildCFG(blk)
    }
  }

  /*
  max1 = max + 1
  l0' = max
  CFG1 = CFG update { pred : { succ = {max} } } union { l0' : { stmts = { if (exp == e1) { goto l1; } else { goto l1'; } }}, succs = { l1,l1'}, preds = preds }  update { l1: { preds += l0' } }
                                                union { l1' : { stmts = { if (exp == e2) { goto l2; } else { goto l2'; } }}, succs = { l2,l2'}, preds = {l0'} }  update { l2: { preds += l1' } }
                                                union { l2' : { stmts = { if (exp == e3) { goto l3; } else { goto l3'; } }}, succs = { l3,l3'}, preds = {l1'} }  update { l3: { preds += l2' } }
                                                ... 
                                                union { ln-1' : { stmts = { if (exp == en) { goto ln; } else { goto l_default; }}, succs = { ln, l_default }, preds = {ln-2'} } update { ln- : { preds += ln-1' }} update { l_default : { preds += ln-1' } } 

  CFG1, max1, {}, false, {}, contNodes, {} |- stmt1,..., stmtn+1 => CFG2, max2, preds2, continable2, breakNodes, contNodes2, {(l1,l1',e1),...,(l_default, _)} 
  -----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------
  CFG, max, preds, continuable, breakNodes, contNodes, caseNodes |- switch exp { stmt1,...,stmtn }   => CFG2, max2, preds2 union breakNodes2 , false, breakNodes, contNodes2, caseNodes
    */
  implicit def blockCFGInstance: CFGClass[Block] = new CFGClass[Block]  {
    override def buildCFG(a: Block)(implicit m:MonadError[SIState,String]): State[StateInfo, Unit] = a match {
      case Block(Nil) => {
        val lhs = Nil
        val rhs = Nil
        for {
          st  <- get;
          max <- m.pure(st.currId);
          currNodeId <- m.pure(internalIdent(s"${labPref}${max}"));
          max1 <- m.pure(max+1);
          cfg0 <- m.pure(st.cfg);
          preds0 <- m.pure(st.currPreds);
          cfgNode <- m.pure(Node(Nil, lhs, rhs, Nil, preds0, Nil, AssignmentNode));
          cfg1p   <- m.pure(preds0.foldLeft(cfg0)( (g,pred)=> {
            val n:Node = g(pred)
            g + (pred -> n.copy(succs = n.succs ++ List(currNodeId)))
          }));
          cfg1    <- m.pure(cfg1p + (currNodeId -> cfgNode));
          _       <- put(st.copy(cfg = cfg1, currId=max1, currPreds=List(currNodeId), continuable=false))
        } yield ()
      }
      case Block(stmts) => for { 
        _ <- stmts.traverse_(stmt => ops.buildCFG(stmt))
        _ <- if (stmts.isEmpty) { m.pure(()) } else { 
          stmts.last match {
            /* the last blockItem is a while. e.g. 
              int f() {
                int c = 0;
                while (1) {
                  if (c > 10)
                    return c;
                  c++;      
                }
                1 == 1; // inserted automatically
              }
            */
            case BlockStmt_(stmt) if isWhileStmt(stmt) || isForStmt(stmt) => {
              val empty:Stmt = Empty
              ops.buildCFG(empty)
            }
            case _ => m.pure(())
          }
        } 
      } yield ()
    }
  }

  implicit def blockStmtCFGInstance:CFGClass[BlockStmt] = new CFGClass[BlockStmt] {
    override def buildCFG(a: BlockStmt)(implicit m:MonadError[SIState,String]): State[StateInfo, Unit] = a match {
      case LocalClass(_) => m.raiseError("Local Class is not supported.") 
      case LocalVars(modifiers, ty, var_decls) => var_decls match {
        case Nil => m.pure(())
        case (var_decl::Nil) =>   
        /*
        CFG1 = CFG update { pred : {stmts = stmts ++ [ty x = exp[]], lVars = lVars ++ [x] } } 
        --------------------------------------------------------
        CFG, max, preds, true |- ty x = exp[] => CFG1, max, [] , false 

        max1 = max + 1
        CFG1 = CFG update { pred : {succ = max} |  pred <- preds } union { max : {ty x = exp[] } } 
        --------------------------------------------------------
        CFG, max, preds, false |- ty x = exp[] => CFG1, max1, [], false 
        */
        for {
          st <- get;
          _  <- if (st.continuable) {
            val cfg0 = st.cfg
            val preds0 = st.currPreds
            val s = a
            val lvars  = getLHSVarsFromVarDecl(var_decl)
            val rvars  = getRHSVarsFromVarDecl(var_decl)
            val cfg1   = preds0.foldLeft(cfg0) ( (g,pred) => {
              val n:Node = g(pred)
              val n1   = n.copy(stmts =n.stmts ++ List(s), localDecls = n.localDecls ++ lvars, lVars = n.lVars ++ lvars, rVars = n.rVars ++ rvars)
              g + (pred -> n1)
            })
            for {
              _ <- put(st.copy(cfg=cfg1))
            } yield ()
          } else {
            m.pure(())
          }

        } yield ()
      }
      case BlockStmt_(stmt) => ops.buildCFG(stmt)
    }
  }


  implicit def stmtCFGInstance:CFGClass[Stmt] = new CFGClass[Stmt] {
    override def buildCFG(a: Stmt)(implicit m:MonadError[SIState,String]): State[StateInfo, Unit] = a match {
      case _ => m.pure(()) // TODO: fixme
    }
  }


  def internalIdent(s:String) :Ident = Ident(s)
  def formalArgsAsDecls(idents:List[Ident], cfg:CFG):CFG = cfg // TODO:fixme
  
  def getLHSVarsFromVarDecl(var_decl:VarDecl):List[Ident] = var_decl match {
    case VarDecl(var_decl_id, var_init) => List(idFromVarDeclId(var_decl_id))
  }
  def getRHSVarsFromVarDecl(var_decl:VarDecl):List[Ident] = var_decl match {
    case VarDecl(var_decl_id, None) => List()
    case VarDecl(var_decl_id, Some(var_init)) => HasVarOps.getVarsFrom(var_init) 
  }

  trait HasVar[A] {
    def getVarsFrom(a:A):List[Ident]
  }

  object HasVarOps {
    def getVarsFrom[A](a:A)(implicit hv:HasVar[A]):List[Ident] = hv.getVarsFrom(a)
  }


  implicit def getVarsFromVarInit:HasVar[VarInit] = new HasVar[VarInit] { 
    override def getVarsFrom(var_init:VarInit):List[Ident] =  var_init match {
      case InitExp(exp) => HasVarOps.getVarsFrom(exp)
      case InitArray(ArrayInit(var_inits)) => var_inits.flatMap(HasVarOps.getVarsFrom(_))
   }
  }
  
  implicit def getVarsFromExp:HasVar[Exp] = new HasVar[Exp] {
    override def getVarsFrom(exp:Exp):List[Ident] = exp match {
      case Lit(lit) => List()
      case ClassLit(ty) => List()
      case This => List()
      case ThisClass(name) => List()
      case InstanceCreation(type_args, type_decl, args, body) => args.flatMap(HasVarOps.getVarsFrom(_))
      case QualInstanceCreation(exp, type_args, id, args, body) => args.flatMap(HasVarOps.getVarsFrom(_))
      case ArrayCreate(ty, exps, num_dims) => exps.flatMap(getVarsFrom(_))
      case ArrayCreateInit(ty, size, init) => init match {
        case ArrayInit(var_inits) => var_inits.flatMap(HasVarOps.getVarsFrom(_))
      }
      case FieldAccess_(access) => HasVarOps.getVarsFrom(access)
      case MethodInv(methodInv) => HasVarOps.getVarsFrom(methodInv) 
      case ArrayAccess(idx)     => HasVarOps.getVarsFrom(idx)
      case ExpName(name)        => HasVarOps.getVarsFrom(name)
      case PostIncrement(exp)   => getVarsFrom(exp)
      case PostDecrement(exp)   => getVarsFrom(exp)
      case PreIncrement(exp)    => getVarsFrom(exp)
      case PreDecrement(exp)    => getVarsFrom(exp)
      case PrePlus(exp)         => getVarsFrom(exp)
      case PreMinus(exp)        => getVarsFrom(exp)
      case PreBitCompl(exp)     => getVarsFrom(exp)
      case PreNot(exp)          => getVarsFrom(exp)
      case Cast(ty, exp)        => getVarsFrom(exp)
      case BinOp(e1, op, e2)    => getVarsFrom(e1) ++ getVarsFrom(e2)
      case InstanceOf(e, ref_type) => getVarsFrom(e)
      case Cond(cond, true_exp, false_exp) => getVarsFrom(cond) ++ getVarsFrom(true_exp) ++ getVarsFrom(false_exp) 
      case Assign(lhs, op, rhs) => HasVarOps.getVarsFrom(lhs) ++ getVarsFrom(rhs)
      case Lambda(params, body) => {
        val ps = HasVarOps.getVarsFrom(params).toSet
        HasVarOps.getVarsFrom(body).filterNot(ps)
      }
      case MethodRef(name, id)  => List()
    }
  }

  implicit def getVarsFromFieldAccess:HasVar[FieldAccess] = new HasVar[FieldAccess] { 
    override def getVarsFrom(field_access:FieldAccess):List[Ident] = List() // TODO: check whether it should indeed empty
  }

  implicit def getVarsFromMethodInvocation:HasVar[MethodInvocation] = new HasVar[MethodInvocation] {
    override def getVarsFrom(methodInv:MethodInvocation):List[Ident] = methodInv match {
      case MethodCall(name, args) => HasVarOps.getVarsFrom(name) ++ args.flatMap(HasVarOps.getVarsFrom(_))
      case PrimaryMethodCall(e,ref_type, id, args) => HasVarOps.getVarsFrom(e) ++ args.flatMap(HasVarOps.getVarsFrom(_))
      case SuperMethodCall(ref_types, id, args) => args.flatMap(HasVarOps.getVarsFrom(_))
      case ClassMethodCall(name, ref_types, id, args) => args.flatMap(HasVarOps.getVarsFrom(_))
      case TypeMethodCall(name, ref_types, id, args) => args.flatMap(HasVarOps.getVarsFrom(_))
    } // TODO:Fixme
  }

  implicit def getVarsFromArrayIndex:HasVar[ArrayIndex] = new HasVar[ArrayIndex] {
    override def getVarsFrom(idx:ArrayIndex):List[Ident] = idx match {
      case ArrayIndex(e, es) => HasVarOps.getVarsFrom(e) ++ es.flatMap(HasVarOps.getVarsFrom(_))
    }
  }

  implicit def getVarsFromName:HasVar[Name] = new HasVar[Name] {
    override def getVarsFrom(n:Name):List[Ident] = n match {
      case Name(ids) => List() // TODO: double check, is it save to assume names are not variable?
    }
  }

  implicit def getVarsFromLhs:HasVar[Lhs] = new HasVar[Lhs] { 
    override def getVarsFrom(lhs:Lhs):List[Ident] = lhs match {
      case NameLhs(name) => HasVarOps.getVarsFrom(name)
      case FieldLhs(field_access) => HasVarOps.getVarsFrom(field_access)
      case ArrayLhs(array_idx) => HasVarOps.getVarsFrom(array_idx)
    }
  }

  implicit def getVarsFromLambdaParams:HasVar[LambdaParams] = new HasVar[LambdaParams] {
    override def getVarsFrom(ps:LambdaParams):List[Ident] = ps match {
      case LambdaSingleParam(id) => List(id)
      case LambdaFormalParams(formal_params) => formal_params.flatMap(HasVarOps.getVarsFrom(_))
      case LambdaInferredParams(ids) => ids
    }
  }

  implicit def getVarsFromLambdaExpression:HasVar[LambdaExpression] = new HasVar[LambdaExpression] {
    override def getVarsFrom(lambExp:LambdaExpression):List[Ident] = lambExp match {
      case LambdaExpression_(e) => HasVarOps.getVarsFrom(e)
      case LambdaBlock(blk) => HasVarOps.getVarsFrom(blk)
    }
  }

  implicit def getVarsFromBlock:HasVar[Block] = new HasVar[Block] {
    override def getVarsFrom(blk:Block):List[Ident] = blk match {
      case Block(stmts) => {
        val localVarStmts = stmts.filter(isLocalVarsBlockStmt(_))
        val others = stmts.filter(!isLocalVarsBlockStmt(_)) 
        val localVars = localVarStmts.flatMap(stmt => stmt match {
          case LocalVars(modifiers,ty, var_decls) => var_decls.flatMap(getLHSVarsFromVarDecl(_))
          case _ => List()
        }).toSet
        val otherVars = others.flatMap(HasVarOps.getVarsFrom(_)) ++ localVarStmts.flatMap(stmt => stmt match {
          case LocalVars(modifiers,ty, var_decls) => var_decls.flatMap(getRHSVarsFromVarDecl(_))
          case _ => List()
        })
        otherVars.filterNot(localVars)
      }
    }
  }

  implicit def getVarsFromBlockStmt:HasVar[BlockStmt] = new HasVar[BlockStmt] {
    override def getVarsFrom(blkStmt:BlockStmt):List[Ident] = blkStmt match {
      case BlockStmt_(stmt) => HasVarOps.getVarsFrom(stmt)
      case LocalClass(class_decl) => List() // TODO:Fixme
      case LocalVars(modifiers, ty, var_decls) => var_decls.flatMap(getRHSVarsFromVarDecl(_))
    }
  }
  
  implicit def getVarsFromStmt:HasVar[Stmt] = new HasVar[Stmt] {
    override def getVarsFrom(stmt:Stmt):List[Ident] = stmt match {
      case _ => List() // TODO: Fixme
    }
  }

  implicit def getVarsFromFormalParam:HasVar[FormalParam] = new HasVar[FormalParam] {
    override def getVarsFrom(fp:FormalParam):List[Ident] = fp match {
      case FormalParam(modifiers, ty, has_arity, var_decl_id) => List(idFromVarDeclId(var_decl_id))
    }
  }

}