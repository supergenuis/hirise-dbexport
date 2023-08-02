package de.soderer.utilities.plugin;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.List;

public class SqlUtil {
    public static List<String> getTableList(String sql){
        Statement statement = null;
        try {
            statement =  CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
        // 获取table
        TablesNamesFinder tablesNamesFinder = new TablesNamesFinder();
        List<String> tableList = tablesNamesFinder.getTableList(statement);
        return tableList;
    }

    public static List<String> getTablewithjoin (String sql){
        Statement statement = null;
        try {
            statement =  CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
        Select selectStatement = (Select) statement;
        PlainSelect plain = (PlainSelect) selectStatement.getSelectBody();
        List<Join> joinList = plain.getJoins();
        List<String> tablewithjoin = new ArrayList<>();
        if (joinList != null) {
            for (int i = 0; i < joinList.size(); i++) {
                tablewithjoin.add(joinList.get(i).toString());
            }
        }
        return tablewithjoin;
    }

    public static String getWhereClause(String sql){
        Statement statement = null;
        try {
            statement =  CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
        Select selectStatement = (Select) statement;
        PlainSelect plain = (PlainSelect) selectStatement.getSelectBody();
        Expression where_expression = plain.getWhere();
        String whereStr = where_expression.toString();
        return whereStr;
    }

    public static List<String> getGroupBy(String sql){
        Statement statement = null;
        try {
            statement =  CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
        Select selectStatement = (Select) statement;
        PlainSelect plain = (PlainSelect) selectStatement.getSelectBody();
        List<Expression> GroupByColumnReferences = plain.getGroupBy().getGroupByExpressions();
        List<String> groupBys = new ArrayList<>();
        if (GroupByColumnReferences != null) {
            for (int i = 0; i < GroupByColumnReferences.size(); i++) {
                groupBys.add(GroupByColumnReferences.get(i).toString());
            }
        }

        return groupBys;
    }

    public static List<String> getOrderBy(String sql){
        Statement statement = null;
        try {
            statement =  CCJSqlParserUtil.parse(sql);
        } catch (JSQLParserException e) {
            e.printStackTrace();
            System.out.println(e.getMessage());
        }
        Select selectStatement = (Select) statement;
        PlainSelect plain = (PlainSelect) selectStatement.getSelectBody();
        List<OrderByElement> OrderByElements = plain.getOrderByElements();
        List<String> orderBys = new ArrayList<>();
        if (OrderByElements != null) {
            for (int i = 0; i < OrderByElements.size(); i++) {
                orderBys.add(OrderByElements.get(i).toString());
            }
        }
        return orderBys;
    }

}
