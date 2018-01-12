package net.globulus.simi;

import net.globulus.simi.api.*;

import java.util.*;

class Interpreter implements BlockInterpreter, Expr.Visitor<SimiValue>, Stmt.Visitor<Object> {

  private final Environment globals = new Environment();
  private Environment environment = globals;
  private final Map<Expr, Integer> locals = new HashMap<>();
  private BaseClassesNativeImpl baseClassesNativeImpl = new BaseClassesNativeImpl();
  private Stack<SimiBlock> loopBlocks = new Stack<>();
  private Stack<SimiException> raisedExceptions = new Stack<>();

  Interpreter() {
    globals.define("clock", new SimiValue.Callable(new SimiCallable() {
      @Override
      public int arity() {
        return 0;
      }

      @Override
      public SimiValue call(BlockInterpreter interpreter,
                         List<SimiValue> arguments) {
        return new SimiValue.Number((double)System.currentTimeMillis() / 1000.0);
      }
    }, "clock", null));
  }

  void interpret(List<Stmt> statements) {
    try {
      for (Stmt statement : statements) {
        if (raisedExceptions.isEmpty()) {
          execute(statement);
        } else {
          throw raisedExceptions.peek();
        }
      }
    } catch (RuntimeError error) {
      Simi.runtimeError(error);
    }
  }

  private SimiValue evaluate(Expr expr) {
    return expr.accept(this);
  }

  private void execute(Stmt stmt) {
    stmt.accept(this);
  }

  void resolve(Expr expr, int depth) {
    locals.put(expr, depth);
  }

  @Override
  public void executeBlock(SimiBlock block, SimiEnvironment environment) {
    Environment previous = this.environment;
    try {
      this.environment = (Environment) environment;
      List<? extends SimiStatement> statements = block.getStatements();
      int size = statements.size();
      for (int i = 0; i < size; i++) {
        if (raisedExceptions.isEmpty()) {
          Stmt statement = (Stmt) statements.get(i);
          execute(statement);
        } else {
          Stmt.Rescue rescue = null;
          for (; i < size; i++) {
            Stmt statement = (Stmt) statements.get(i);
            if (statement instanceof Stmt.Rescue) {
              rescue = (Stmt.Rescue) statement;
              break;
            }
          }
          if (rescue != null) {
            SimiException e = raisedExceptions.pop();
            executeRescueBlock(rescue, e);
          }
        }
      }
    } finally {
      this.environment = previous;
    }
  }

  @Override
  public SimiValue getGlobal(String name) {
    return globals.getAt(0, name);
  }

  @Override
  public SimiEnvironment getEnvironment() {
    return environment;
  }

  @Override
  public void raiseException(SimiException e) {
    raisedExceptions.push(e);
  }

  @Override
  public SimiValue visitBlockExpr(Expr.Block stmt, boolean newScope) {
    executeBlock(stmt, new Environment(environment));
    return null;
  }

  @Override
  public Void visitBreakStmt(Stmt.Break stmt) {
    if (loopBlocks.isEmpty()) {
      Simi.error(stmt.name, "Break outside a loop!");
    }
    throw new Break();
  }

  @Override
  public Void visitClassStmt(Stmt.Class stmt) {
      String className = stmt.name.lexeme;
      boolean isBaseClass = isBaseClass(className);
      if (isBaseClass) {
          globals.define(className, null);
      } else {
          environment.define(className, null);
      }
      List<SimiClassImpl> superclasses = null;
      if (stmt.superclasses != null) {
        superclasses = new ArrayList<>();
        for (Expr superclass : stmt.superclasses) {
            SimiObject clazz = evaluate(superclass).getObject();
            if (!(clazz instanceof SimiClassImpl)) {
                throw new RuntimeError(stmt.name, "Superclass must be a class.");
            }
            superclasses.add((SimiClassImpl) clazz);
        }
      } else if (!isBaseClass) {
          superclasses = Collections.singletonList((SimiClassImpl) globals.tryGet(Constants.CLASS_OBJECT).getObject());
      }
      environment = new Environment(environment);
      environment.define(Constants.SUPER, new SimiClassImpl.SuperClassesList(superclasses));

      Map<String, SimiValue> constants = new HashMap<>();
      for (Expr.Assign constant : stmt.constants) {
          String key = constant.name.lexeme;
          SimiValue value = evaluate(constant.value);
          constants.put(key, value);
      }

    Map<OverloadableFunction, SimiFunction> methods = new HashMap<>();
    for (Stmt.Function method : stmt.methods) {
        String name = method.name.lexeme;
      SimiFunction function = new SimiFunction(method, environment,
          name.equals(Constants.INIT), method.block.isNative());
      methods.put(new OverloadableFunction(name, function.arity()), function);
    }

    SimiClassImpl klass = new SimiClassImpl(className, superclasses, constants, methods);

//    if (superclass != null) {
//      environment = environment.enclosing;
//    }

    if (isBaseClass) {
        globals.assign(stmt.name, new SimiValue.Object(klass), false);
    } else {
        environment.assign(stmt.name, new SimiValue.Object(klass), false);
    }
    return null;
  }

  @Override
  public Void visitContinueStmt(Stmt.Continue stmt) {
    if (loopBlocks.isEmpty()) {
      Simi.error(stmt.name, "Continue outside a loop!");
    }
    throw new Continue();
  }

  @Override
  public Void visitExpressionStmt(Stmt.Expression stmt) {
    evaluate(stmt.expression);
    return null; // [void]
  }

  @Override
  public SimiValue visitFunctionStmt(Stmt.Function stmt) {
    SimiFunction function = new SimiFunction(stmt, environment, false, stmt.block.isNative());
    SimiValue value = new SimiValue.Callable(function, stmt.name.lexeme, null);
    environment.define(stmt.name.lexeme, value);
    return value;
  }

  @Override
  public Object visitElsifStmt(Stmt.Elsif stmt) {
    if (isTruthy(evaluate(stmt.condition))) {
      evaluate(stmt.thenBranch);
      return true;
    }
    return false;
  }

  @Override
  public Void visitIfStmt(Stmt.If stmt) {
    if ((Boolean) visitElsifStmt(stmt.ifstmt)) {
      return null;
    }
    for (Stmt.Elsif elsif : stmt.elsifs) {
      if ((Boolean) visitElsifStmt(elsif)) {
        return null;
      }
    }
    if (stmt.elseBranch != null) {
      evaluate(stmt.elseBranch);
    }
    return null;
  }

  @Override
  public Void visitPrintStmt(Stmt.Print stmt) {
    SimiValue value = evaluate(stmt.expression);
    System.out.println(stringify(value));
    return null;
  }

  @Override
  public Void visitRescueStmt(Stmt.Rescue stmt) {
    executeRescueBlock(stmt, null);
    return null;
  }

  @Override
  public Void visitReturnStmt(Stmt.Return stmt) {
    SimiValue value = null;
    if (stmt.value != null) {
      value = evaluate(stmt.value);
    }

    throw new Return(value);
  }

  @Override
  public Void visitWhileStmt(Stmt.While stmt) {
    loopBlocks.push(stmt.body);
    while (isTruthy(evaluate(stmt.condition))) {
      try {
        evaluate(stmt.body);
      } catch (Break b) {
        break;
      } catch (Continue ignored) { }
    }
    loopBlocks.pop();
    return null;
  }

  @Override
  public Void visitForStmt(Stmt.For stmt) {
    Environment previous = this.environment;
    this.environment = new Environment(this.environment);
    List<Expr> emptyArgs = new ArrayList<>();
    SimiObjectImpl iterable = (SimiObjectImpl) SimiObjectImpl.getOrConvertObject(evaluate(stmt.iterable), this);
    Token nextToken = new Token(TokenType.IDENTIFIER, Constants.NEXT, null, stmt.var.name.line);
    SimiValue nextMethod = iterable.get(nextToken, 0, environment);
    if (nextMethod == null) {
      Token iterateToken = new Token(TokenType.IDENTIFIER, Constants.ITERATE, null, stmt.var.name.line);
      SimiObjectImpl iterator = (SimiObjectImpl) call(iterable.get(iterateToken, 0, environment), emptyArgs, iterateToken).getObject();
      nextMethod = iterator.get(nextToken, 0, environment);
    }

    loopBlocks.push(stmt.body);
    while (true) {
      SimiValue var = call(nextMethod, emptyArgs, nextToken);
      if (var == null) {
        break;
      }
      environment.assign(stmt.var.name, var, true);
      try {
        evaluate(stmt.body);
      } catch (Break b) {
        break;
      } catch (Continue ignored) { }
    }
    loopBlocks.pop();
    this.environment = previous;
    return null;
  }

    @Override
  public SimiValue visitAssignExpr(Expr.Assign expr) {
    SimiValue value;
    if (expr.value instanceof Expr.Block) {
      value = visitFunctionStmt(new Stmt.Function(expr.name, (Expr.Block) expr.value));
    } else {
      value = evaluate(expr.value);
    }
    if (value instanceof SimiValue.String || value instanceof SimiValue.Number) {
      value = value.copy();
    }
    Integer distance = locals.get(expr);
    if (distance != null) {
      environment.assignAt(distance, expr.name, value);
    } else {
      globals.assign(expr.name, value, false);
    }
    return value;
  }

  @Override
  public SimiValue visitBinaryExpr(Expr.Binary expr) {
    SimiValue left = evaluate(expr.left);
    SimiValue right = evaluate(expr.right); // [left]

    switch (expr.operator.type) {
      case BANG_EQUAL: return new SimiValue.Number(!isEqual(left, right, expr));
      case EQUAL_EQUAL: return new SimiValue.Number(isEqual(left, right, expr));
      case LESS_GREATER: return compare(left, right, expr);
        case IS:
            return new SimiValue.Number(isInstance(left, right, expr));
        case ISNOT:
            return new SimiValue.Number(!isInstance(left, right, expr));
        case IN:
            return new SimiValue.Number(isIn(left, right, expr));
        case NOTIN:
            return new SimiValue.Number(!isIn(left, right, expr));
      case GREATER:
        checkNumberOperands(expr.operator, left, right);
        return new SimiValue.Number(left.getNumber() > right.getNumber());
      case GREATER_EQUAL:
        checkNumberOperands(expr.operator, left, right);
          return new SimiValue.Number(left.getNumber() >= right.getNumber());
      case LESS:
        checkNumberOperands(expr.operator, left, right);
          return new SimiValue.Number(left.getNumber() < right.getNumber());
      case LESS_EQUAL:
        checkNumberOperands(expr.operator, left, right);
          return new SimiValue.Number(left.getNumber() <= right.getNumber());
      case MINUS:
        checkNumberOperands(expr.operator, left, right);
          return new SimiValue.Number(left.getNumber() - right.getNumber());
      case PLUS:
        if (left instanceof SimiValue.Number && right instanceof SimiValue.Number) {
            return new SimiValue.Number(left.getNumber() + right.getNumber());
        } // [plus]

        if (left instanceof SimiValue.String && right instanceof SimiValue.String) {
            return new SimiValue.String(left.getString() + right.getString());
        }

        throw new RuntimeError(expr.operator,
            "Operands must be two numbers or two strings.");
      case SLASH:
        checkNumberOperands(expr.operator, left, right);
          return new SimiValue.Number(left.getNumber() / right.getNumber());
      case STAR:
        checkNumberOperands(expr.operator, left, right);
          return new SimiValue.Number(left.getNumber() * right.getNumber());
        case MOD:
            checkNumberOperands(expr.operator, left, right);
            return new SimiValue.Number(left.getNumber() % right.getNumber());
      case QUESTION_QUESTION:
        return (left != null) ? left : right;
    }

    // Unreachable.
    return null;
  }

  @Override
  public SimiValue visitCallExpr(Expr.Call expr) {
    SimiValue callee = evaluate(expr.callee);
    return call(callee, expr.arguments, expr.paren);
  }

  private SimiValue call(SimiValue callee, List<Expr> args, Token paren) {
    List<SimiValue> arguments = new ArrayList<>();
    for (Expr arg : args) { // [in-order]
      SimiValue value;
      if (arg instanceof Expr.Block) {
        value = new SimiValue.Callable(new BlockImpl((Expr.Block) arg, environment), null, null);
      } else {
        value = evaluate(arg);
      }
      arguments.add(value);
    }
    return call(callee, paren, arguments);
  }

  private SimiValue call(SimiValue callee, Token paren, List<SimiValue> arguments) {
    SimiCallable callable;
    String methodName;
    SimiObject instance;
    if (callee instanceof SimiValue.Object) {
      SimiObject value = callee.getObject();
      if (!(value instanceof SimiClassImpl)) {
        throw new RuntimeError(paren,"Can only call functions and classes.");
      }
      return ((SimiClassImpl) value).init(this, arguments);
    } else if (callee instanceof SimiValue.Callable) {
      callable = callee.getCallable();
      methodName = ((SimiValue.Callable) callee).name;
      instance = ((SimiValue.Callable) callee).getInstance();
    } else {
      throw new RuntimeError(paren,"Can only call functions and classes.");
    }

    if (arguments.size() != callable.arity()) {
      throw new RuntimeError(paren, "Expected " +
              callable.arity() + " arguments but got " +
              arguments.size() + ".");
    }
    boolean isNative = callable instanceof SimiFunction && ((SimiFunction) callable).isNative
            || callable instanceof SimiMethod && ((SimiMethod) callable).function.isNative
            || callable instanceof BlockImpl && ((BlockImpl) callable).isNative();
    if (isNative) {
      if (instance != null) {
        SimiClassImpl clazz;
        if (callable instanceof SimiMethod) {
          clazz = ((SimiMethod) callable).clazz;
        } else {
          if (instance instanceof SimiClassImpl) {
            clazz = (SimiClassImpl) instance;
          } else {
            clazz = (SimiClassImpl) instance.getSimiClass();
          }
        }
        String className = isBaseClass(clazz.name) ? clazz.name : Constants.CLASS_OBJECT; // TODO fix to check external JARs before attempting $Object
        SimiCallable nativeMethod = baseClassesNativeImpl.get(className, methodName, callable.arity());
        if (nativeMethod == null) {
          nativeMethod = baseClassesNativeImpl.get(Constants.CLASS_GLOBALS, methodName, callable.arity());
        }
        List<SimiValue> nativeArgs = new ArrayList<>();
        nativeArgs.add(new SimiValue.Object(instance));
        nativeArgs.addAll(arguments);
        return nativeMethod.call(this, nativeArgs);
      } else {
        // TODO globals
      }
    }
    return callable.call(this, arguments);
  }

  @Override
  public SimiValue visitGetExpr(Expr.Get expr) {
    SimiValue object = evaluate(expr.object);
    Token name = evaluateGetSetName(expr.origin, expr.name);
    try {
        SimiObject simiObject = SimiObjectImpl.getOrConvertObject(object, this);
        if (simiObject instanceof SimiObjectImpl) {
          return ((SimiObjectImpl) simiObject).get(name, expr.arity, environment);
        } else {
          return simiObject.get(name.lexeme, environment);
        }
    } catch (SimiValue.IncompatibleValuesException e) {
        throw new RuntimeError(expr.origin,"Only instances have properties.");
    }
  }

  private Token evaluateGetSetName(Token origin, Expr name) {
    if (name instanceof Expr.Variable) {
      return ((Expr.Variable) name).name;
    } else {
      SimiValue val = evaluate(name);
      String lexeme;
      if (val instanceof SimiValue.Number) {
        lexeme = "" + val.getNumber();
      } else if (val instanceof SimiValue.String) {
        lexeme = val.getString();
      } else {
        throw new RuntimeError(origin,"Unable to parse getter/setter, invalid value: " + val.toString());
      }
      return new Token(TokenType.IDENTIFIER, lexeme, null, origin.line);
    }
  }

  @Override
  public SimiValue visitGroupingExpr(Expr.Grouping expr) {
    return evaluate(expr.expression);
  }

  @Override
  public SimiValue visitLiteralExpr(Expr.Literal expr) {
    return expr.value;
  }

  @Override
  public SimiValue visitLogicalExpr(Expr.Logical expr) {
    SimiValue left = evaluate(expr.left);

    if (expr.operator.type == TokenType.OR) {
      if (isTruthy(left)) return left;
    } else {
      if (!isTruthy(left)) return left;
    }

    return evaluate(expr.right);
  }

  @Override
  public SimiValue visitSetExpr(Expr.Set expr) {
    SimiValue object = evaluate(expr.object);

    if (!(object instanceof SimiValue.Object)) { // [order]
      throw new RuntimeError(expr.origin, "Only objects have fields.");
    }

    Token name = evaluateGetSetName(expr.origin, expr.name);
    SimiValue value;
    if (expr.value instanceof Expr.Block) {
      value = new SimiValue.Callable(new BlockImpl((Expr.Block) expr.value, environment), name.lexeme, object.getObject());
    } else {
      value = evaluate(expr.value);
    }
    ((SimiObjectImpl) object.getObject()).set(name, value, environment);
    return value;
  }

  @Override
  public SimiValue visitSuperExpr(Expr.Super expr) {
    int distance = locals.get(expr);
    SimiClassImpl superclass = (SimiClassImpl) environment.getAt(distance, Constants.SUPER).getObject();

    // "self" is always one level nearer than "super"'s environment.
    SimiObjectImpl object = (SimiObjectImpl) environment.getAt(distance - 1, Constants.SELF).getObject();

    SimiMethod method = superclass.findMethod(object, expr.method.lexeme, expr.arity);

    if (method == null) {
      throw new RuntimeError(expr.method,
          "Undefined property '" + expr.method.lexeme + "'.");
    }

    return new SimiValue.Callable(method, expr.method.lexeme, object);
  }

  @Override
  public SimiValue visitSelfExpr(Expr.Self expr) {
    return lookUpVariable(expr.keyword, expr);
  }

  @Override
  public SimiValue visitUnaryExpr(Expr.Unary expr) {
    SimiValue right = evaluate(expr.right);

    switch (expr.operator.type) {
        case NOT:
        return new SimiValue.Number(!isTruthy(right));
      case MINUS:
        checkNumberOperand(expr.operator, right);
        return new SimiValue.Number(-right.getNumber());
    }
    // Unreachable.
    return null;
  }

  @Override
  public SimiValue visitVariableExpr(Expr.Variable expr) {
    return lookUpVariable(expr.name, expr);
  }

    @Override
    public SimiValue visitObjectLiteralExpr(Expr.ObjectLiteral expr) {
        boolean immutable = (expr.opener.type == TokenType.LEFT_BRACKET);
        LinkedHashMap<String, SimiValue> fields = new LinkedHashMap<>();
        int count = 0;
        for (Expr prop : expr.props) {
            String key;
            Expr valueExpr;
            if (expr.isDictionary) {
                Expr.Assign assign = (Expr.Assign) prop;
                key = assign.name.lexeme;
              valueExpr = assign.value;
            } else {
              key = Constants.IMPLICIT + count;
              valueExpr = prop;
            }
            SimiValue value;
            if (valueExpr instanceof Expr.Block) {
              value = new SimiValue.Callable(new BlockImpl((Expr.Block) valueExpr, environment), key, null);
            } else {
              value = evaluate(valueExpr);
            }
            fields.put(key, value);
            count++;
        }
        SimiObjectImpl object =
                new SimiObjectImpl((SimiClassImpl) globals.tryGet(Constants.CLASS_OBJECT).getObject(), fields, immutable);
        for (Map.Entry<String, SimiValue> entry : fields.entrySet()) {
          if (entry.getValue() instanceof SimiValue.Callable) {
            ((SimiValue.Callable) entry.getValue()).bind(object);
          }
        }
        return new SimiValue.Object(object);
    }

    private void executeRescueBlock(Stmt.Rescue rescue, SimiException e) {
      List<SimiValue> args = new ArrayList<>();
      if (e != null) {
        args.add(new SimiValue.Object(e));
      } else {
        args.add(null);
      }
      call(new SimiValue.Callable(new BlockImpl(rescue.block, this.environment), null, null), rescue.keyword, args);
    }

    private SimiValue lookUpVariable(Token name, Expr expr) {
//        Integer distance = locals.get(expr);
        SimiValue value = null;
//        if (distance != null) {
//          value = environment.getAt(distance, name.lexeme);
//        }
//        if (value == null) {
          value = environment.tryGet(name.lexeme);
          if (value != null) {
            return value;
          }
//        }
        return globals.get(name);
    }

  private void checkNumberOperand(Token operator, SimiValue operand) {
    if (operand instanceof SimiValue.Number) {
      return;
    }
    throw new RuntimeError(operator, "Operand must be a number.");
  }

  private void checkNumberOperands(Token operator, SimiValue left, SimiValue right) {
    if (left instanceof SimiValue.Number && right instanceof SimiValue.Number) {
      return;
    }
    throw new RuntimeError(operator, "Operands must be numbers.");
  }

  static boolean isTruthy(SimiValue object) {
    if (object == null) {
        return false;
    }
    try {
        double value = object.getNumber();
        return value != 0;
    } catch (SimiValue.IncompatibleValuesException e) {
        return true;
    }
  }

  private boolean isEqual(SimiValue a, SimiValue b, Expr.Binary expr) {
    // nil is only equal to nil.
    if (a == null && b == null) {
      return true;
    }
    if (a == null) {
      return false;
    }
    if (a instanceof SimiValue.Object) {
      Token equals = new Token(TokenType.IDENTIFIER, Constants.EQUALS, null, expr.operator.line);
      return call(((SimiObjectImpl) a.getObject()).get(equals, 1, environment), equals, Arrays.asList(a, b)).getNumber() != 0;
    }
    return a.equals(b);
  }

  private SimiValue compare(SimiValue a, SimiValue b, Expr.Binary expr) {
    // nil is only equal to nil.
    if (a == null && b == null) {
      return SimiValue.Number.TRUE;
    }
    if (a == null) {
      return SimiValue.Number.FALSE;
    }
    if (a instanceof SimiValue.Object) {
      Token compareTo = new Token(TokenType.IDENTIFIER, Constants.COMPARE_TO, null, expr.operator.line);
      return call(((SimiObjectImpl) a.getObject()).get(compareTo, 1, environment), compareTo, Arrays.asList(a, b));
    }
    return new SimiValue.Number(a.compareTo(b));
  }

  private boolean isInstance(SimiValue a, SimiValue b, Expr.Binary expr) {
    if (a == null || b == null) {
      return false;
    }
    if (!(a instanceof SimiValue.Object)) {
      throw new RuntimeError(expr.operator, "Left side must be an Object!");
    }
    if (!(b instanceof SimiValue.Object)) {
        throw new RuntimeError(expr.operator, "Right side must be a Class!");
    }
    return ((SimiObjectImpl) a.getObject()).is((SimiClassImpl) b.getObject());
  }

  private boolean isIn(SimiValue a, SimiValue b, Expr.Binary expr) {
      SimiObjectImpl object;
      if (b instanceof SimiValue.Object) {
        object = ((SimiObjectImpl) b.getObject());
      } else {
        object = (SimiObjectImpl) SimiObjectImpl.getOrConvertObject(b, this);
//          throw new RuntimeError(expr.operator, "Right side must be an Object!");
      }
      Token has = new Token(TokenType.IDENTIFIER, Constants.HAS, null, expr.operator.line);
      return call(object.get(has, 1, environment), has, Collections.singletonList(a)).getNumber() != 0;
  }

  private String stringify(SimiValue object) {
    if (object == null) {
      return "nil";
    }
    return object.toString();
  }

  private boolean isBaseClass(String className) {
    return className.equals(Constants.CLASS_OBJECT)
            || className.equals(Constants.CLASS_NUMBER)
            || className.equals(Constants.CLASS_STRING)
            || className.equals(Constants.CLASS_EXCEPTION);
  }
}
