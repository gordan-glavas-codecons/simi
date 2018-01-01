package net.globulus.simi;

import net.globulus.simi.api.SimiCallable;
import net.globulus.simi.api.BlockInterpreter;
import net.globulus.simi.api.SimiValue;

import java.util.List;

class SimiFunction implements SimiCallable {

  private Stmt.Function declaration;
  private final BlockImp block;
  private final boolean isInitializer;
  private final boolean isNative;

  SimiFunction(Stmt.Function declaration,
               Environment closure,
               boolean isInitializer,
               boolean isNative) {
    this.declaration = declaration;
    this.block = new BlockImp(declaration.block, closure);
    this.isInitializer = isInitializer;
    this.isNative = isNative;
  }

  private SimiFunction(Stmt.Function declaration,
                       BlockImp block,
                       boolean isInitializer,
                       boolean isNative) {
      this.declaration = declaration;
      this.block = block;
      this.isInitializer = isInitializer;
      this.isNative = isNative;
  }

  SimiFunction bind(SimiObjectImpl instance) {
      block.bind(instance);
      return new SimiFunction(declaration, block, isInitializer, isNative);
  }

  @Override
  public String toString() {
    return "<fn " + declaration.name.lexeme + ">";
  }

  @Override
  public int arity() {
    return block.arity();
  }

  @Override
  public Object call(BlockInterpreter interpreter, List<SimiValue> arguments, boolean immutable) {
    block.call(interpreter, arguments, immutable);
    if (isInitializer) {
        return block.closure.getAt(0, Constants.SELF);
    }
    return null;
  }
}
