package com.linkedin.coral.hive.hive2rel.functions;

import com.linkedin.coral.com.google.common.base.Preconditions;
import com.linkedin.coral.com.google.common.collect.ImmutableList;
import com.linkedin.coral.hive.hive2rel.HiveTable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.calcite.sql.SqlBinaryOperator;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlPrefixOperator;
import org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.calcite.sql.SqlUnresolvedFunction;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.validate.SqlUserDefinedFunction;
import org.apache.hadoop.hive.metastore.api.Table;

import static com.google.common.base.Preconditions.*;
import static org.apache.calcite.sql.parser.SqlParserPos.*;


/**
 * Class to resolve hive function names in SQL to HiveFunction.
 */
public class HiveFunctionResolver {

  public HiveFunctionRegistry registry;
  private final List<SqlOperator> operators;

  public HiveFunctionResolver(HiveFunctionRegistry registry) {
    this.registry = registry;
    this.operators = new ArrayList<>(SqlStdOperatorTable.instance().getOperatorList());
    operators.add(HiveRLikeOperator.REGEXP);
    operators.add(HiveRLikeOperator.RLIKE);
  }

  /**
   * Resolves {@code name} to calcite unary operator.
   * @param name operator text (Ex: '-')
   * @return SqlOperator matching input name
   * @throws IllegalStateException if there is zero or more than one matching operator
   */
  public SqlOperator resolveUnaryOperator(String name) {
    final String lowerCaseOperator = name.toLowerCase();
    List<SqlOperator> matches = operators.stream()
        .filter(o -> o.getName().toLowerCase().equals(lowerCaseOperator)
            && o instanceof SqlPrefixOperator)
        .collect(Collectors.toList());
    checkState(matches.size() == 1, "%s operator %s",
        operators.isEmpty() ? "Unknown" : "Ambiguous", name);
    return matches.get(0);
  }

  /**
   * Resolves {@code name} to calcite binary operator
   * @param name operator text (Ex: '+' or 'LIKE')
   * @return SqlOperator matching input name
   * @throws IllegalStateException if there are zero or more than one matching operator
   */
  public SqlOperator resolveBinaryOperator(String name) {
    final String lowerCaseOperator = name.toLowerCase();
    List<SqlOperator> matches = operators.stream()
        .filter(o -> o.getName().toLowerCase().equals(lowerCaseOperator)
            && (o instanceof SqlBinaryOperator || o instanceof SqlSpecialOperator))
        .collect(Collectors.toList());
    if (matches.size() == 0) {
      HiveFunction f = tryResolve(lowerCaseOperator, false, null);
      if (f != null) {
        matches.add(f.getSqlOperator());
      }
    }

    // CORAL-23: For `+` and `-` we have 2 matching operators for each:
    // SqlMonotonicBinaryOperator and SqlDatetimePlusOperator for `+`
    // SqlMonotonicBinaryOperator and SqlDatetimeSubtractionOperator for `-`
    // Hive does not support the SqlDatetime[Plus|Subtraction]Operator operator, so we return SqlMonotonicBinaryOperator by default.
    if (lowerCaseOperator.equals("+")) {
      return SqlStdOperatorTable.PLUS;
    } else if (lowerCaseOperator.equals("-")) {
      return SqlStdOperatorTable.MINUS;
    }

    checkState(matches.size() == 1, "%s operator %s",
        operators.isEmpty() ? "Unknown" : "Ambiguous", name);
    return matches.get(0);
  }

  /**
   * Resolves hive function name to specific HiveFunction. This method
   * first attempts to resolve function by its name. If there is no match,
   * this attempts to match dali-style function names (DB_TABLE_VERSION_FUNCTION).
   * Right now, this method does not validate parameters leaving it to
   * the subsequent validator and analyzer phases to validate parameter types.
   * @param functionName hive function name
   * @param isCaseSensitive is function name case-sensitive
   * @param hiveTable handle to Hive table representing metastore information. This is used for resolving
   *                  Dali function names, which are resolved using table parameters
   * @return resolved hive functions
   * @throws UnknownSqlFunctionException if the function name can not be resolved.
   */
  public HiveFunction tryResolve(@Nonnull String functionName, boolean isCaseSensitive, @Nullable Table hiveTable) {
    checkNotNull(functionName);
    Collection<HiveFunction> functions = registry.lookup(functionName, isCaseSensitive);
    if (functions.isEmpty() && hiveTable != null) {
      functions = tryResolveAsDaliFunction(functionName, hiveTable);
    }
    if (functions.isEmpty()) {
      throw new UnknownSqlFunctionException(functionName);
    }
    if (functions.size() == 1) {
      return functions.iterator().next();
    }
    // we've overloaded function names. Calcite will resolve overload later during semantic analysis.
    // For now, create a placeholder SqlNode for the function. We want to use Dali class name as function
    // name if this is overloaded function name.
    return unresolvedFunction(functions.iterator().next().getSqlOperator().getName(), hiveTable);
  }

  /**
   * Resolves function to concrete operator.
   * @param functionName function name to resolve
   * @param isCaseSensitive is the function name case-sensitive
   * @return list of matching HiveFunctions or empty list if there is no match
   */
  public Collection<HiveFunction> resolve(String functionName, boolean isCaseSensitive) {
    return registry.lookup(functionName, isCaseSensitive);
  }

  /**
   * Tries to resolve function name as Dali function name using the provided Hive table catalog information.
   * This uses table parameters 'function' property to resolve the function name to the implementing class.
   * @param functionName function name to resolve
   * @param table Hive metastore table handle
   * @return list of matching HiveFunctions or empty list if the function name is not in the
   *   dali function name format of db_tableName_functionName
   * @throws UnknownSqlFunctionException if the function name is in Dali function name format but there is no mapping
   */
  public Collection<HiveFunction> tryResolveAsDaliFunction(String functionName, @Nonnull Table table) {
    Preconditions.checkNotNull(table);
    String functionPrefix = String.format("%s_%s_", table.getDbName(), table.getTableName());
    if (!functionName.toLowerCase().startsWith(functionPrefix.toLowerCase())) {
      // Don't throw UnknownSqlFunctionException here because this is not a dali function
      // and this method is trying to resolve only Dali functions
      return ImmutableList.of();
    }
    String funcBaseName = functionName.substring(functionPrefix.length());
    HiveTable hiveTable = new HiveTable(table);
    Map<String, String> functionParams = hiveTable.getDaliFunctionParams();
    String funcClassName = functionParams.get(funcBaseName);
    if (funcClassName == null) {
      return ImmutableList.of();
    }
    List<String> dependencies = hiveTable.getDaliFunctionDependency();

    final Collection<HiveFunction> hiveFunctions = registry.lookup(funcClassName, true);
    if (hiveFunctions.size() == 0) {
      // we want to see class name in the exception message for coverage testing
      // so throw exception here
      throw new UnknownSqlFunctionException(funcClassName);
    }

    // As we have some information collected from Dali view definition (or hive table),
    // we want to save them into a SqlOperator object for later reference.
    // We need to replace the function entry in the local registry copy.
    Collection<HiveFunction> resolvedHiveFuncs = new ArrayList<>();

    // In order to avoid java.util.ConcurrentModificationException, we need to use another LinkedList
    // as iterator in the for loop
    LinkedList<HiveFunction> iteratorFuncs = new LinkedList<>();
    for (HiveFunction hiveFunc : hiveFunctions) {
      iteratorFuncs.add(hiveFunc);
    }
    for (int i = 0; i < iteratorFuncs.size(); ++i) {
      HiveFunction hiveFunc = iteratorFuncs.get(i);
      SqlUserDefinedFunction sqlUdf = (SqlUserDefinedFunction) hiveFunc.getSqlOperator();
      SqlOperator resolvedDaliSqlUdf = new DaliSqlUserDefinedFunction(sqlUdf, dependencies, functionName);
      HiveFunction resolvedHiveFunc = new HiveFunction(hiveFunc.getHiveFunctionName(), resolvedDaliSqlUdf, hiveFunc.getUdfDependencies());
      resolvedHiveFuncs.add(resolvedHiveFunc);
      ((StaticHiveFunctionRegistry) registry).replaceFunctionEntry(funcClassName, hiveFunc, resolvedHiveFunc);
    }
    return resolvedHiveFuncs;
  }

  private @Nonnull HiveFunction unresolvedFunction(String functionName, Table table) {
    SqlIdentifier funcIdentifier = createFunctionIdentifier(functionName, table);
    return new HiveFunction(functionName,
        new SqlUnresolvedFunction(funcIdentifier, null, null,
            null, null, SqlFunctionCategory.USER_DEFINED_FUNCTION));
  }

  private @Nonnull SqlIdentifier createFunctionIdentifier(String functionName, @Nullable Table table) {
    if (table == null) {
      return new SqlIdentifier(ImmutableList.of(functionName), ZERO);
    } else {
      return new SqlFunctionIdentifier(functionName, ImmutableList.of(table.getDbName(), table.getTableName()));
    }
  }
}
