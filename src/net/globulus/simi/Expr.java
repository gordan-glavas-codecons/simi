package net.globulus.simi;

import net.globulus.simi.api.SimiBlock;
import net.globulus.simi.api.SimiStatement;
import net.globulus.simi.api.SimiValue;

import java.util.List;

abstract class Expr {

  interface Visitor<R> {
    R visitBlockExpr(Block expr, boolean newScope);
    R visitAssignExpr(Assign expr);
    R visitBinaryExpr(Binary expr);
    R visitCallExpr(Call expr);
    R visitGetExpr(Get expr);
    R visitGroupingExpr(Grouping expr);
    R visitLiteralExpr(Literal expr);
    R visitLogicalExpr(Logical expr);
    R visitSetExpr(Set expr);
    R visitSuperExpr(Super expr);
    R visitSelfExpr(Self expr);
    R visitUnaryExpr(Unary expr);
    R visitVariableExpr(Variable expr);
    R visitObjectLiteralExpr(ObjectLiteral expr);
  }

    static class Block extends Expr implements SimiBlock {
        Block(List<Token> params, List<Stmt> statements) {
            this.params = params;
            this.statements = statements;
        }

        <R> R accept(Visitor<R> visitor, Object... params) {
            boolean newScope = (params.length == 0) ? true : (Boolean) params[0];
            return visitor.visitBlockExpr(this, newScope);
        }

        final List<Token> params;
        final List<Stmt> statements;

      @Override
      public List<? extends SimiStatement> getStatements() {
        return statements;
      }

      public boolean isEmpty() {
        if (statements.size() != 1) {
          return false;
        }
        Stmt stmt = statements.get(0);
        if (!(stmt instanceof Stmt.Expression)) {
          return false;
        }
        Stmt.Expression expr = (Stmt.Expression) stmt;
        if (!(expr.expression instanceof Expr.Literal)) {
          return false;
        }
        return ((Expr.Literal) expr.expression).value instanceof Pass;
      }
    }

  static class Assign extends Expr {
    Assign(Token name, Expr value) {
      this.name = name;
      this.value = value;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitAssignExpr(this);
    }

    final Token name;
    final Expr value;
  }
  static class Binary extends Expr {
    Binary(Expr left, Token operator, Expr right) {
      this.left = left;
      this.operator = operator;
      this.right = right;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitBinaryExpr(this);
    }

    final Expr left;
    final Token operator;
    final Expr right;
  }

  static class Call extends Expr {
    Call(Expr callee, Token paren, List<Expr> arguments) {
      this.callee = callee;
      this.paren = paren;
      this.arguments = arguments;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitCallExpr(this);
    }

    final Expr callee;
    final Token paren;
    final List<Expr> arguments;
  }

  static class Get extends Expr {
    Get(Expr object, Token name, Integer arity) {
      this.object = object;
      this.name = name;
      this.arity = arity;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitGetExpr(this);
    }

    final Expr object;
    final Token name;
    final Integer arity;
  }
  static class Grouping extends Expr {
    Grouping(Expr expression) {
      this.expression = expression;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitGroupingExpr(this);
    }

    final Expr expression;
  }
  static class Literal extends Expr {
    Literal(SimiValue value) {
      this.value = value;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitLiteralExpr(this);
    }

    final SimiValue value;
  }

  static class Logical extends Expr {
    Logical(Expr left, Token operator, Expr right) {
      this.left = left;
      this.operator = operator;
      this.right = right;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitLogicalExpr(this);
    }

    final Expr left;
    final Token operator;
    final Expr right;
  }

  static class Set extends Expr {
    Set(Expr object, Token name, Expr value) {
      this.object = object;
      this.name = name;
      this.value = value;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitSetExpr(this);
    }

    final Expr object;
    final Token name;
    final Expr value;
  }

  static class Super extends Expr {
    Super(Token keyword, Token method, Integer arity) {
      this.keyword = keyword;
      this.method = method;
      this.arity = arity;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitSuperExpr(this);
    }

    final Token keyword;
    final Token method;
    final Integer arity;
  }
  static class Self extends Expr {
    Self(Token keyword) {
      this.keyword = keyword;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitSelfExpr(this);
    }

    final Token keyword;
  }
  static class Unary extends Expr {
    Unary(Token operator, Expr right) {
      this.operator = operator;
      this.right = right;
    }

    <R> R accept(Visitor<R> visitor, Object... params) {
      return visitor.visitUnaryExpr(this);
    }

    final Token operator;
    final Expr right;
  }
    static class Variable extends Expr {
        Variable(Token name) {
            this.name = name;
        }

        <R> R accept(Visitor<R> visitor, Object... params) {
            return visitor.visitVariableExpr(this);
        }

        final Token name;
    }

    static class ObjectLiteral extends Expr {
        ObjectLiteral(Token opener, List<Expr> props) {
            this.opener = opener;
            this.props = props;
        }

        <R> R accept(Visitor<R> visitor, Object... params) {
            return visitor.visitObjectLiteralExpr(this);
        }

        final Token opener;
        final List<Expr> props;
    }

  abstract <R> R accept(Visitor<R> visitor, Object... params);
}
