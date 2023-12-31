package de.soderer.dbexport.worker;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.soderer.dbexport.DbExport;
import de.soderer.dbexport.DbExportException;
import de.soderer.dbexport.converter.CassandraDBValueConverter;
import de.soderer.dbexport.converter.DefaultDBValueConverter;
import de.soderer.dbexport.converter.FirebirdDBValueConverter;
import de.soderer.dbexport.converter.MariaDBValueConverter;
import de.soderer.dbexport.converter.MySQLDBValueConverter;
import de.soderer.dbexport.converter.OracleDBValueConverter;
import de.soderer.dbexport.converter.PostgreSQLDBValueConverter;
import de.soderer.dbexport.converter.SQLiteDBValueConverter;
import de.soderer.utilities.DateUtilities;
import de.soderer.utilities.Utilities;
import de.soderer.utilities.collection.CaseInsensitiveMap;
import de.soderer.utilities.collection.CaseInsensitiveSet;
import de.soderer.utilities.db.DatabaseConstraint;
import de.soderer.utilities.db.DatabaseForeignKey;
import de.soderer.utilities.db.DatabaseIndex;
import de.soderer.utilities.db.DbColumnType;
import de.soderer.utilities.db.DbDefinition;
import de.soderer.utilities.db.DbUtilities;
import de.soderer.utilities.db.SimpleDataType;
import de.soderer.utilities.db.DbUtilities.DbVendor;
import de.soderer.utilities.json.JsonArray;
import de.soderer.utilities.json.JsonObject;
import de.soderer.utilities.json.JsonWriter;
import de.soderer.utilities.worker.WorkerDual;
import de.soderer.utilities.worker.WorkerParentDual;
import de.soderer.utilities.zip.Zip4jUtilities;
import de.soderer.utilities.zip.ZipUtilities;

public abstract class AbstractDbExportWorker extends WorkerDual<Boolean> {
	// Mandatory parameters
	protected DbDefinition dbDefinition = null;
	private boolean isStatementFile = false;
	private String sqlStatementOrTablelist;
	private String outputpath;
	private ByteArrayOutputStream guiOutputStream = null;

	// Default optional parameters
	protected boolean log = false;
	protected boolean zip = false;
	protected char[] zipPassword = null;
	protected boolean useZipCrypto = false;
	protected Charset encoding = StandardCharsets.UTF_8;
	protected boolean createBlobFiles = false;
	protected boolean createClobFiles = false;
	protected Locale dateFormatLocale = Locale.getDefault();
	protected String dateFormatPattern;
	protected String dateTimeFormatPattern;
	protected NumberFormat decimalFormat ;
	protected Character decimalSeparator;
	protected boolean beautify = false;
	protected boolean exportStructure = false;

	private int overallExportedLines = 0;
	private long overallExportedDataAmountRaw = 0;
	private long overallExportedDataAmountCompressed = 0;

	private String databaseTimeZone = TimeZone.getDefault().getID();
	private String exportDataTimeZone = TimeZone.getDefault().getID();

	private DefaultDBValueConverter dbValueConverter;

	{
		// Create the default number format
		decimalFormat = NumberFormat.getNumberInstance(dateFormatLocale);
		decimalFormat.setGroupingUsed(false);
	}

	public AbstractDbExportWorker(final WorkerParentDual parent, final DbDefinition dbDefinition, final boolean isStatementFile, final String sqlStatementOrTablelist, final String outputpath) {
		super(parent);
		this.dbDefinition = dbDefinition;
		this.isStatementFile = isStatementFile;
		this.sqlStatementOrTablelist = sqlStatementOrTablelist;
		this.outputpath = outputpath;
	}

	public void setLog(final boolean log) {
		this.log = log;
	}

	public void setZip(final boolean zip) {
		this.zip = zip;
	}

	public void setZipPassword(final char[] zipPassword) {
		this.zipPassword = zipPassword;
	}

	public void setUseZipCrypto(final boolean useZipCrypto) {
		this.useZipCrypto = useZipCrypto;
	}

	public void setDatabaseTimeZone(final String databaseTimeZone) {
		this.databaseTimeZone = databaseTimeZone;
		if (this.databaseTimeZone == null) {
			this.databaseTimeZone = TimeZone.getDefault().getID();
		}
	}

	public void setExportDataTimeZone(final String exportDataTimeZone) {
		this.exportDataTimeZone = exportDataTimeZone;
		if (this.exportDataTimeZone == null) {
			this.exportDataTimeZone = TimeZone.getDefault().getID();
		}
	}

	public void setEncoding(final Charset encoding) {
		this.encoding = encoding;
	}

	public void setCreateBlobFiles(final boolean createBlobFiles) {
		this.createBlobFiles = createBlobFiles;
	}

	public void setCreateClobFiles(final boolean createClobFiles) {
		this.createClobFiles = createClobFiles;
	}

	public void setDateFormatLocale(final Locale dateFormatLocale) {
		this.dateFormatLocale = dateFormatLocale;
		dateFormatterCache = null;
		dateTimeFormatterCache = null;
	}

	public void setBeautify(final boolean beautify) {
		this.beautify = beautify;
	}

	public void setExportStructure(final boolean exportStructure) {
		this.exportStructure = exportStructure;
	}

	public void setDateFormat(final String dateFormat) {
		if (dateFormat != null) {
			dateFormatPattern = dateFormat;
			dateFormatterCache = null;
		}
	}

	public void setDateTimeFormat(final String dateTimeFormat) {
		if (dateTimeFormat != null) {
			dateTimeFormatPattern = dateTimeFormat;
			dateTimeFormatterCache = null;
		}
	}

	public void setDecimalSeparator(final Character decimalSeparator) {
		if (decimalSeparator != null) {
			this.decimalSeparator = decimalSeparator;
		}
	}

	private DateTimeFormatter dateFormatterCache = null;
	protected DateTimeFormatter getDateFormatter() {
		if (dateFormatterCache == null) {
			if (Utilities.isNotBlank(dateFormatPattern)) {
				dateFormatterCache = DateTimeFormatter.ofPattern(dateFormatPattern);
			} else {
				dateFormatterCache = DateTimeFormatter.ofPattern("yyyy-MM-dd");
			}
			if (dateFormatLocale != null) {
				dateFormatterCache.withLocale(dateFormatLocale);
			}
			dateFormatterCache.withResolverStyle(ResolverStyle.STRICT);
		}
		return dateFormatterCache;
	}

	private DateTimeFormatter dateTimeFormatterCache = null;
	protected DateTimeFormatter getDateTimeFormatter() {
		if (dateTimeFormatterCache == null) {
			if (Utilities.isNotBlank(dateTimeFormatPattern)) {
				dateTimeFormatterCache = DateTimeFormatter.ofPattern(dateTimeFormatPattern);
			} else {
				dateTimeFormatterCache = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
			}
			if (dateFormatLocale != null) {
				dateTimeFormatterCache.withLocale(dateFormatLocale);
			}
			dateTimeFormatterCache.withResolverStyle(ResolverStyle.STRICT);
		}
		return dateTimeFormatterCache;
	}

	@Override
	public Boolean work() throws Exception {
		overallExportedLines = 0;

		dbDefinition.checkParameters(DbExport.APPLICATION_NAME, DbExport.CONFIGURATION_FILE);

		switch (dbDefinition.getDbVendor()) {
			case Oracle:
				dbValueConverter = new OracleDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case SQLite:
				dbValueConverter = new SQLiteDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case MySQL:
				dbValueConverter = new MySQLDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case MariaDB:
				dbValueConverter = new MariaDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case PostgreSQL:
				dbValueConverter = new PostgreSQLDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case Firebird:
				dbValueConverter = new FirebirdDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case Cassandra:
				dbValueConverter = new CassandraDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case Derby:
				dbValueConverter = new DefaultDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case HSQL:
				dbValueConverter = new DefaultDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			case MsSQL:
				dbValueConverter = new DefaultDBValueConverter(zip, zipPassword, useZipCrypto, createBlobFiles, createClobFiles, getFileExtension());
				break;
			default:
				throw new Exception("Unsupported db vendor: null");
		}

		try (Connection connection = DbUtilities.createConnection(dbDefinition, true)) {
			if (isStatementFile) {
				if (Utilities.isBlank(sqlStatementOrTablelist)) {
					throw new DbExportException("Statementfile is missing");
				} else {
					sqlStatementOrTablelist = Utilities.replaceUsersHome(sqlStatementOrTablelist);
					if (!new File(sqlStatementOrTablelist).exists()) {
						throw new DbExportException("Statementfile does not exist");
					} else {
						sqlStatementOrTablelist = new String(Utilities.readFileToByteArray(new File(sqlStatementOrTablelist)), StandardCharsets.UTF_8);
					}
				}
			}

			if (Utilities.isBlank(sqlStatementOrTablelist)) {
				throw new DbExportException("SqlStatement or tablelist is missing");
			}

			if (sqlStatementOrTablelist.toLowerCase().startsWith("select ")
					|| sqlStatementOrTablelist.toLowerCase().startsWith("select\t")
					|| sqlStatementOrTablelist.toLowerCase().startsWith("select\n")
					|| sqlStatementOrTablelist.toLowerCase().startsWith("select\r")) {
				if (!"console".equalsIgnoreCase(outputpath) && !"gui".equalsIgnoreCase(outputpath)) {
					if (!new File(outputpath).exists()) {
						final int lastSeparator = Math.max(outputpath.lastIndexOf("/"), outputpath.lastIndexOf("\\"));
						if (lastSeparator >= 0) {
							String filename = outputpath.substring(lastSeparator + 1);
							filename = DateUtilities.replaceDatePatternInString(filename, LocalDateTime.now());
							outputpath = outputpath.substring(0, lastSeparator + 1) + filename;
						}
					}

					if (new File(outputpath).exists() && new File(outputpath).isDirectory()) {
						outputpath = outputpath + File.separator + "export_" + DateUtilities.formatDate("yyyy-MM-dd_HH-mm-ss", LocalDateTime.now());
					}
				}

				if (exportStructure) {
					exportDbStructure(connection, sqlStatementOrTablelist, outputpath);
				} else {
					export(connection, sqlStatementOrTablelist, outputpath);
				}

				return !cancel;
			} else {
				signalItemStart("Scanning tables ...", null);
				signalUnlimitedProgress();
				final List<String> tablesToExport = DbUtilities.getAvailableTables(connection, sqlStatementOrTablelist);
				if ("*".equals(sqlStatementOrTablelist)) {
					Collections.sort(tablesToExport);
				}
				if (tablesToExport.size() == 0) {
					throw new DbExportException("No table found for export");
				}
				itemsToDo = tablesToExport.size();
				itemsDone = 0;
				//创建文件夹，如果文件夹不存在@20230704 by huangzh
				File folder = new File(outputpath);
				if (!folder.exists() && !folder.isDirectory()) {
					folder.mkdirs();
					System.out.println("创建文件夹:[ "+outputpath+"]");
				} else {
					System.out.println("文件夹:["+outputpath+"]已存在");
				}
				if (exportStructure) {
					if (new File(outputpath).exists() && new File(outputpath).isDirectory()) {
						outputpath = outputpath + File.separator + "dbstructure_" + DateUtilities.formatDate("yyyy-MM-dd_HH-mm-ss", LocalDateTime.now());
					}
					exportDbStructure(connection, tablesToExport, outputpath);
				} else {
					for (int i = 0; i < tablesToExport.size() && !cancel; i++) {
						signalProgress(true);
						final String tableName = tablesToExport.get(i).toLowerCase();
						subItemsToDo = 0;
						subItemsDone = 0;
						signalItemStart(tableName, null);

						String nextOutputFilePath = outputpath;
						if ("console".equalsIgnoreCase(outputpath)) {
							System.out.println("Table: " + tableName);
						} else if ("gui".equalsIgnoreCase(outputpath)) {
							System.out.println("Table: " + tableName);
						} else {
							nextOutputFilePath = outputpath + File.separator + tableName.toLowerCase();
						}
						final List<String> columnNames = new ArrayList<>(DbUtilities.getColumnNames(connection, tableName));
						Collections.sort(columnNames);
						final List<String> keyColumnNames = new ArrayList<>(DbUtilities.getPrimaryKeyColumns(connection, tableName));
						Collections.sort(keyColumnNames);
						final List<String> readoutColumns = new ArrayList<>();
						readoutColumns.addAll(keyColumnNames);
						for (final String columnName : columnNames) {
							if (!readoutColumns.contains(columnName)) {
								readoutColumns.add(columnName);
							}
						}

						final List<String> escapedKeyColumns = new ArrayList<>();
						for (final String unescapedKeyColumnName : keyColumnNames) {
							escapedKeyColumns.add(DbUtilities.escapeVendorReservedNames(dbDefinition.getDbVendor(), unescapedKeyColumnName));
						}

						final List<String> escapedReadoutColumns = new ArrayList<>();
						for (final String unescapedColumnName : readoutColumns) {
							escapedReadoutColumns.add(DbUtilities.escapeVendorReservedNames(dbDefinition.getDbVendor(), unescapedColumnName));
						}

						String orderPart = "";
						if (!keyColumnNames.isEmpty()) {
							orderPart = " ORDER BY " + Utilities.join(escapedKeyColumns, ", ");
						}

						final String sqlStatement = "SELECT " + Utilities.join(escapedReadoutColumns, ", ") + " FROM " + tableName + orderPart;

						try {
							export(connection, sqlStatement, nextOutputFilePath);
						} catch (final Exception e) {
							throw new Exception("Error occurred while exporting\n" + sqlStatement + "\n" + e.getMessage(), e);
						}

						signalItemDone();

						itemsDone++;
					}
				}
				return !cancel;
			}
		} catch (final Exception e) {
			throw e;
		} finally {
			if (dbDefinition.getDbVendor() == DbVendor.Derby) {
				DbUtilities.shutDownDerbyDb(dbDefinition.getDbName());
			}
		}
	}

	private void exportDbStructure(final Connection connection, final List<String> tablesToExport, String outputFilePath) throws Exception {
		OutputStream outputStream = null;

		try {
			if ("console".equalsIgnoreCase(outputFilePath)) {
				outputStream = System.out;
			} else if ("gui".equalsIgnoreCase(outputFilePath)) {
				guiOutputStream = new ByteArrayOutputStream();
				outputStream = guiOutputStream;
			} else {
				if (zip) {
					if (!outputFilePath.toLowerCase().endsWith(".zip")) {
						if (!outputFilePath.toLowerCase().endsWith(".json")) {
							outputFilePath = outputFilePath + ".json";
						}

						outputFilePath = outputFilePath + ".zip";
					}
				} else if (!outputFilePath.toLowerCase().endsWith(".json")) {
					outputFilePath = outputFilePath + ".json";
				}

				if (new File(outputFilePath).exists()) {
					throw new DbExportException("Outputfile already exists: " + outputFilePath);
				}

				if (zip) {
					outputStream = ZipUtilities.openNewZipOutputStream(new FileOutputStream(new File(outputFilePath)));
					String entryFileName = outputFilePath.substring(0, outputFilePath.length() - 4);
					entryFileName = entryFileName.substring(entryFileName.lastIndexOf(File.separatorChar) + 1);
					if (!entryFileName.toLowerCase().endsWith(".json")) {
						entryFileName += ".json";
					}
					final ZipEntry entry = new ZipEntry(entryFileName);
					entry.setTime(ZonedDateTime.now().toInstant().toEpochMilli());
					((ZipOutputStream) outputStream).putNextEntry(entry);
				} else {
					outputStream = new FileOutputStream(new File(outputFilePath));
				}
			}

			signalProgress();

			try (JsonWriter jsonWriter = new JsonWriter(outputStream)) {
				jsonWriter.openJsonObject();
				for (int i = 0; i < tablesToExport.size() && !cancel; i++) {
					final JsonObject tableJsonObject = createTableStructureJsonObject(connection, tablesToExport.get(i).toLowerCase());

					jsonWriter.openJsonObjectProperty(tablesToExport.get(i).toLowerCase());
					jsonWriter.add(tableJsonObject);

					itemsDone++;
					signalProgress();
				}
				jsonWriter.closeJsonObject();
			}
		} finally {
			Utilities.closeQuietly(outputStream);

			if (zip && zipPassword != null) {
				Zip4jUtilities.createPasswordSecuredZipFile(outputFilePath, zipPassword, useZipCrypto);
			}
		}
	}

	private static JsonObject createTableStructureJsonObject(final Connection connection, final String tablename) throws Exception {
		final CaseInsensitiveSet keyColumns = DbUtilities.getPrimaryKeyColumns(connection, tablename);
		final CaseInsensitiveMap<DbColumnType> dbColumns = DbUtilities.getColumnDataTypes(connection, tablename);
		final List<DatabaseForeignKey> foreignKeys = DbUtilities.getForeignKeys(connection, tablename);
		final List<DatabaseIndex> indices = DbUtilities.getIndices(connection, tablename);
		final List<DatabaseConstraint> constraints = DbUtilities.getConstraints(connection, tablename);
		final CaseInsensitiveMap<String> defaultValues = DbUtilities.getColumnDefaultValues(connection, tablename);

		final JsonObject tableJsonObject = new JsonObject();

		// Keycolumns list
		if (!keyColumns.isEmpty()) {
			final JsonArray keyColumnsJsonArray = new JsonArray();
			tableJsonObject.add("keycolumns", keyColumnsJsonArray);
			for (final String keyColumnName : Utilities.asSortedList(keyColumns)) {
				if (keyColumns.contains(keyColumnName)) {
					keyColumnsJsonArray.add(keyColumnName.toLowerCase());
				}
			}
		}

		final JsonArray columnsJsonArray = new JsonArray();
		tableJsonObject.add("columns", columnsJsonArray);

		// Keycolumns datatypes
		for (final String columnName : Utilities.asSortedList(dbColumns.keySet())) {
			if (keyColumns.contains(columnName)) {
				columnsJsonArray.add(createColumnJsonObject(columnName, dbColumns.get(columnName), defaultValues.get(columnName)));
			}
		}

		// Other columns datatypes
		for (final String columnName : Utilities.asSortedList(dbColumns.keySet())) {
			if (!keyColumns.contains(columnName)) {
				columnsJsonArray.add(createColumnJsonObject(columnName, dbColumns.get(columnName), defaultValues.get(columnName)));
			}
		}

		// Indices
		if (indices != null && !indices.isEmpty()) {
			final JsonArray indicesJsonArray = new JsonArray();
			tableJsonObject.add("indices", indicesJsonArray);
			for (final DatabaseIndex index : indices) {
				final JsonObject indexJsonObject = new JsonObject();
				indicesJsonArray.add(indexJsonObject);
				if (index.getIndexName() != null) {
					indexJsonObject.add("name", index.getIndexName());
				}
				final JsonArray indexedColumnsJsonArray = new JsonArray();
				indexJsonObject.add("indexedColumns", indexedColumnsJsonArray);
				for (final String columnName : index.getIndexedColumns()) {
					indexedColumnsJsonArray.add(columnName);
				}
			}
		}

		// Foreign keys
		if (foreignKeys != null && !foreignKeys.isEmpty()) {
			final JsonArray foreignKeysJsonArray = new JsonArray();
			tableJsonObject.add("foreignKeys", foreignKeysJsonArray);
			for (final DatabaseForeignKey foreignKey : foreignKeys) {
				final JsonObject foreignKeyJsonObject = new JsonObject();
				foreignKeysJsonArray.add(foreignKeyJsonObject);
				if (foreignKey.getForeignKeyName() != null) {
					foreignKeyJsonObject.add("name", foreignKey.getForeignKeyName());
				}
				foreignKeyJsonObject.add("columnName", foreignKey.getColumnName());
				foreignKeyJsonObject.add("referencedTable", foreignKey.getReferencedTableName());
				foreignKeyJsonObject.add("referencedColumn", foreignKey.getReferencedColumnName());
			}
		}

		// Constraints
		if (constraints != null && !constraints.isEmpty()) {
			final JsonArray constraintsJsonArray = new JsonArray();
			tableJsonObject.add("constraints", constraintsJsonArray);
			for (final DatabaseConstraint constraint : constraints) {
				final JsonObject constraintJsonObject = new JsonObject();
				constraintsJsonArray.add(constraintJsonObject);
				if (constraint.getConstraintName() != null) {
					constraintJsonObject.add("name", constraint.getConstraintName());
				}
				constraintJsonObject.add("type", constraint.getConstraintType().name());
				if (constraint.getColumnName() != null) {
					constraintJsonObject.add("columnName", constraint.getColumnName());
				}
			}
		}

		return tableJsonObject;
	}

	private static JsonObject createColumnJsonObject(final String columnName, final DbColumnType columnType, final String defaultValue) {
		final JsonObject columnJsonObject = new JsonObject();

		columnJsonObject.add("name", columnName.toLowerCase());
		columnJsonObject.add("datatype", columnType.getSimpleDataType().name());
		if (columnType.getSimpleDataType() == SimpleDataType.String) {
			columnJsonObject.add("datasize", columnType.getCharacterByteSize());
		}
		if (!columnType.isNullable()) {
			columnJsonObject.add("nullable", columnType.isNullable());
		}
		if (defaultValue != null) {
			columnJsonObject.add("defaultvalue", defaultValue);
		}
		columnJsonObject.add("databasevendorspecific_datatype", columnType.getTypeName());

		return columnJsonObject;
	}

	private void exportDbStructure(final Connection connection, final String sqlStatement, String outputFilePath) throws Exception {
		OutputStream outputStream = null;
		try {
			if ("console".equalsIgnoreCase(outputFilePath)) {
				outputStream = System.out;
			} else if ("gui".equalsIgnoreCase(outputFilePath)) {
				guiOutputStream = new ByteArrayOutputStream();
				outputStream = guiOutputStream;
			} else {
				if (zip) {
					if (!outputFilePath.toLowerCase().endsWith(".zip")) {
						if (!outputFilePath.toLowerCase().endsWith(".json")) {
							outputFilePath = outputFilePath + ".json";
						}

						outputFilePath = outputFilePath + ".zip";
					}
				} else if (!outputFilePath.toLowerCase().endsWith(".json")) {
					outputFilePath = outputFilePath + ".json";
				}

				if (new File(outputFilePath).exists()) {
					throw new DbExportException("Outputfile already exists: " + outputFilePath);
				}

				if (zip) {
					outputStream = ZipUtilities.openNewZipOutputStream(new FileOutputStream(new File(outputFilePath)));
					String entryFileName = outputFilePath.substring(0, outputFilePath.length() - 4);
					entryFileName = entryFileName.substring(entryFileName.lastIndexOf(File.separatorChar) + 1);
					if (!entryFileName.toLowerCase().endsWith(".json")) {
						entryFileName += ".json";
					}
					final ZipEntry entry = new ZipEntry(entryFileName);
					entry.setTime(ZonedDateTime.now().toInstant().toEpochMilli());
					((ZipOutputStream) outputStream).putNextEntry(entry);
				} else {
					outputStream = new FileOutputStream(new File(outputFilePath));
				}
			}
			try (JsonWriter jsonWriter = new JsonWriter(outputStream)) {
				jsonWriter.openJsonObject();

				final JsonArray columnsJsonArray = new JsonArray();

				try (Statement statement = connection.createStatement()) {
					statement.setFetchSize(100);
					try (ResultSet resultSet = statement.executeQuery(sqlStatement)) {
						final ResultSetMetaData metaData = resultSet.getMetaData();
						for (int i = 1; i <= metaData.getColumnCount(); i ++) {
							final JsonObject columnJsonObject = new JsonObject();
							columnsJsonArray.add(columnJsonObject);

							columnJsonObject.add("name", metaData.getColumnName(i));
							columnJsonObject.add("datatype", DbUtilities.getTypeNameById(metaData.getColumnType(i)));
							columnJsonObject.add("databasevendorspecific_datatype", metaData.getColumnTypeName(i));
						}
					}
				}

				jsonWriter.openJsonObjectProperty("statement");
				jsonWriter.addSimpleJsonObjectPropertyValue(sqlStatement);

				jsonWriter.openJsonObjectProperty("columns");
				jsonWriter.add(columnsJsonArray);

				jsonWriter.closeJsonObject();
			}
		} finally {
			Utilities.closeQuietly(outputStream);

			if (zip && zipPassword != null) {
				Zip4jUtilities.createPasswordSecuredZipFile(outputFilePath, zipPassword, useZipCrypto);
			}
		}
	}

	private void export(final Connection connection, final String sqlStatement, String outputFilePath) throws Exception {
		OutputStream outputStream = null;
		OutputStream logOutputStream = null;
		boolean errorOccurred = false;
		boolean fileWasCreated = false;
		try {
			if ("console".equalsIgnoreCase(outputFilePath)) {
				outputStream = System.out;
			} else if ("gui".equalsIgnoreCase(outputFilePath)) {
				guiOutputStream = new ByteArrayOutputStream();
				outputStream = guiOutputStream;
			} else {
				if (zip) {
					if (!outputFilePath.toLowerCase().endsWith(".zip")) {
						if (!outputFilePath.toLowerCase().endsWith("." + getFileExtension())) {
							outputFilePath = outputFilePath + "." + getFileExtension();
						}

						outputFilePath = outputFilePath + ".zip";
					}
				} else if (!outputFilePath.toLowerCase().endsWith("." + getFileExtension())) {
					outputFilePath = outputFilePath + "." + getFileExtension();
				}

				if (new File(outputFilePath).exists()) {
					throw new DbExportException("Outputfile already exists: " + outputFilePath);
				}

				if (log) {
					logOutputStream = new FileOutputStream(new File(outputFilePath + "." + DateUtilities.formatDate("yyyy-MM-dd_HH-mm-ss", LocalDateTime.now()) + ".log"));

					logToFile(logOutputStream, getConfigurationLogString(new File(outputFilePath).getName(), sqlStatement)
							+ (Utilities.isNotBlank(dateFormatPattern) ? "DateFormatPattern: " + dateFormatPattern + "\n" : "")
							+ (Utilities.isNotBlank(dateTimeFormatPattern) ? "DateTimeFormatPattern: " + dateTimeFormatPattern + "\n" : "")
							+ (databaseTimeZone != null && !databaseTimeZone.equals(exportDataTimeZone) ? "DatabaseZoneId: " + databaseTimeZone + "\nExportDataZoneId: " + exportDataTimeZone + "\n" : ""));
				}

				if (currentItemName == null) {
					logToFile(logOutputStream, "Start: " + DateUtilities.formatDate(DateUtilities.getDateTimeFormatWithSecondsPattern(Locale.getDefault()), getStartTime()));
				} else {
					logToFile(logOutputStream, "Start: " + DateUtilities.formatDate(DateUtilities.getDateTimeFormatWithSecondsPattern(Locale.getDefault()), startTimeSub));
				}

				if (zip) {
					outputStream = ZipUtilities.openNewZipOutputStream(new FileOutputStream(new File(outputFilePath)));
					String entryFileName = outputFilePath.substring(0, outputFilePath.length() - 4);
					entryFileName = entryFileName.substring(entryFileName.lastIndexOf(File.separatorChar) + 1);
					if (!entryFileName.toLowerCase().endsWith("." + getFileExtension())) {
						entryFileName += "." + getFileExtension();
					}
					final ZipEntry entry = new ZipEntry(entryFileName);
					entry.setTime(ZonedDateTime.now().toInstant().toEpochMilli());
					((ZipOutputStream) outputStream).putNextEntry(entry);
				} else {
					outputStream = new FileOutputStream(new File(outputFilePath));
				}
				fileWasCreated = true;
			}

			if (currentItemName == null) {
				signalUnlimitedProgress();
			} else {
				signalUnlimitedSubProgress();
			}

			try (Statement statement = DbUtilities.getStatementForLargeQuery(connection)) {
				String countSqlStatementString = "SELECT COUNT(*) FROM (" + sqlStatement + ") data";
				if (dbDefinition.getDbVendor() == DbVendor.Cassandra || dbDefinition.getDbVendor() == DbVendor.MsSQL) {
					if (sqlStatement.toLowerCase().contains(" order by ")) {
						countSqlStatementString = "SELECT COUNT(*)" + sqlStatement.substring(sqlStatement.toLowerCase().indexOf(" from "), sqlStatement.toLowerCase().indexOf(" order by "));
					} else {
						countSqlStatementString = "SELECT COUNT(*)" + sqlStatement.substring(sqlStatement.toLowerCase().indexOf(" from "));
					}
				}
				System.out.println("countSqlStatementString-->"+countSqlStatementString);
				try (ResultSet resultSet = statement.executeQuery(countSqlStatementString)) {
					resultSet.next();
					final int linesToExport = resultSet.getInt(1);
					logToFile(logOutputStream, "Lines to export: " + linesToExport);

					if (currentItemName == null) {
						itemsToDo = linesToExport;
						signalProgress();
					} else {
						subItemsToDo = linesToExport;
						signalItemProgress();
					}
				}

				openWriter(outputStream);

				try (ResultSet resultSet = statement.executeQuery(sqlStatement)) {
					final ResultSetMetaData metaData = resultSet.getMetaData();

					// Scan headers
					final List<String> columnNames = new ArrayList<>();
					final List<String> columnTypes = new ArrayList<>();
					for (int i = 1; i <= metaData.getColumnCount(); i++) {
						columnNames.add(metaData.getColumnName(i));
						columnTypes.add(metaData.getColumnTypeName(i));
					}

					if (currentItemName == null) {
						itemsDone = 0;
						signalProgress();
					} else {
						subItemsDone = 0;
						signalItemProgress();
					}

					if (currentItemName == null) {
						signalProgress();
					} else {
						signalItemProgress();
					}
					System.out.println("sqlStatement-->"+sqlStatement);
					startOutput(connection, sqlStatement, columnNames);

					// Write values
					while (resultSet.next() && !cancel) {
						startTableLine();
						for (int columnIndex = 1; columnIndex <= metaData.getColumnCount(); columnIndex++) {
							final String columnName = metaData.getColumnName(columnIndex);
							Object value = dbValueConverter.convert(metaData, resultSet, columnIndex, outputFilePath);
							if (value != null && value instanceof Date && metaData.getColumnType(columnIndex) == Types.DATE) {
								value = DateUtilities.changeDateTimeZone((Date) value, ZoneId.of(databaseTimeZone), ZoneId.of(exportDataTimeZone));
								writeDateColumn(columnName, DateUtilities.getLocalDateForDate((Date) value));
							} else if (value != null && value instanceof LocalDateTime && metaData.getColumnType(columnIndex) == Types.DATE) {
								value = DateUtilities.changeDateTimeZone((LocalDateTime) value, ZoneId.of(databaseTimeZone), ZoneId.of(exportDataTimeZone));
								writeDateColumn(columnName, ((LocalDateTime) value).toLocalDate());
							} else if (value != null && value instanceof LocalDate) {
								writeDateColumn(columnName, (LocalDate) value);
							} else if (value != null && value instanceof ZonedDateTime) {
								value = DateUtilities.changeDateTimeZone((ZonedDateTime) value, ZoneId.of(exportDataTimeZone));
								writeDateColumn(columnName, ((ZonedDateTime) value).toLocalDate());
							} else if (value != null && value instanceof Date) {
								value = DateUtilities.changeDateTimeZone((Date) value, ZoneId.of(databaseTimeZone), ZoneId.of(exportDataTimeZone));
								writeDateTimeColumn(columnName, DateUtilities.getLocalDateTimeForDate((Date) value));
							} else if (value != null && value instanceof LocalDateTime) {
								value = DateUtilities.changeDateTimeZone((LocalDateTime) value, ZoneId.of(databaseTimeZone), ZoneId.of(exportDataTimeZone));
								writeDateTimeColumn(columnName, (LocalDateTime) value);
							} else if (value != null && value instanceof ZonedDateTime) {
								value = DateUtilities.changeDateTimeZone((ZonedDateTime) value, ZoneId.of(exportDataTimeZone));
								writeDateTimeColumn(columnName, (ZonedDateTime) value);
							} else if (value != null && value instanceof File) {
								if (zip) {
									overallExportedDataAmountRaw += ZipUtilities.getDataSizeUncompressed((File) value);
									overallExportedDataAmountCompressed += ((File) value).length();
								} else {
									overallExportedDataAmountRaw += ((File) value).length();
								}
								value = ((File) value).getName();
								writeColumn(columnName, value);
							} else {
								writeColumn(columnName, value);
							}
						}
						endTableLine();

						if (currentItemName == null) {
							itemsDone++;
							signalProgress();
						} else {
							subItemsDone++;
							signalItemProgress();
						}
					}

					if (cancel) {
						// Statement must be cancelled, or the "ResultSet.close()" will wait for all remaining data to be read
						statement.cancel();
					}

					endOutput();
				}

				closeWriter();

				long exportedLines;
				if (currentItemName == null) {
					exportedLines = itemsDone;
				} else {
					exportedLines = subItemsDone;
				}

				if (currentItemName == null) {
					setEndTime(LocalDateTime.now());
				} else {
					endTimeSub = LocalDateTime.now();
				}

				if (exportedLines > 0) {
					logToFile(logOutputStream, "Exported lines: " + exportedLines);

					long elapsedTimeInSeconds;
					if (currentItemName == null) {
						elapsedTimeInSeconds = Duration.between(getStartTime(), getEndTime()).getSeconds();
					} else {
						elapsedTimeInSeconds = Duration.between(startTimeSub, endTimeSub).getSeconds();
					}
					if (elapsedTimeInSeconds > 0) {
						final int linesPerSecond = (int) (exportedLines / elapsedTimeInSeconds);
						logToFile(logOutputStream, "Export speed: " + linesPerSecond + " lines/second");
					} else {
						logToFile(logOutputStream, "Export speed: immediately");
					}

					if (new File(outputFilePath).exists()) {
						logToFile(logOutputStream, "Exported data amount: " + Utilities.getHumanReadableNumber(new File(outputFilePath).length(), "Byte", false, 5, false, Locale.ENGLISH));
					}
				}

				if (currentItemName == null) {
					logToFile(logOutputStream, "End: " + DateUtilities.formatDate(DateUtilities.getDateTimeFormatWithSecondsPattern(Locale.getDefault()), getEndTime()));
					logToFile(logOutputStream, "Time elapsed: " + DateUtilities.getHumanReadableTimespanEnglish(Duration.between(getStartTime(), getEndTime()), true));
				} else {
					logToFile(logOutputStream, "End: " + DateUtilities.formatDate(DateUtilities.getDateTimeFormatWithSecondsPattern(Locale.getDefault()), endTimeSub));
					logToFile(logOutputStream, "Time elapsed: " + DateUtilities.getHumanReadableTimespanEnglish(Duration.between(startTimeSub, endTimeSub), true));
				}

				overallExportedLines += exportedLines;
			}
		} catch (final SQLException sqle) {
			errorOccurred = true;
			throw new DbExportException("SQL error: " + sqle.getMessage(), sqle);
		} catch (final Exception e) {
			errorOccurred = true;
			try {
				logToFile(logOutputStream, "Error: " + e.getMessage());
			} catch (final Exception e1) {
				e1.printStackTrace();
			}
			throw e;
		} finally {
			closeWriter();

			Utilities.closeQuietly(outputStream);
			Utilities.closeQuietly(logOutputStream);

			if (errorOccurred && fileWasCreated && new File(outputFilePath).exists() && overallExportedLines == 0) {
				new File(outputFilePath).delete();
			} else if (cancel && fileWasCreated && new File(outputFilePath).exists()) {
				new File(outputFilePath).delete();
			}
		}

		if (new File(outputFilePath).exists()) {
			if (zip && zipPassword != null) {
				Zip4jUtilities.createPasswordSecuredZipFile(outputFilePath, zipPassword, useZipCrypto);
			}

			final File exportedFile = new File(outputFilePath);
			if (zip) {
				overallExportedDataAmountRaw += Zip4jUtilities.getUncompressedSize(exportedFile, zipPassword);
				overallExportedDataAmountCompressed += (exportedFile).length();
			} else {
				overallExportedDataAmountRaw += (exportedFile).length();
			}
		}
	}

	private static void logToFile(final OutputStream logOutputStream, final String message) throws Exception {
		if (logOutputStream != null) {
			logOutputStream.write((message + "\n").getBytes(StandardCharsets.UTF_8));
		}
	}

	public int getOverallExportedLines() {
		return overallExportedLines;
	}

	public long getOverallExportedDataAmountRaw() {
		return overallExportedDataAmountRaw;
	}

	public long getOverallExportedDataAmountCompressed() {
		return overallExportedDataAmountCompressed;
	}

	public ByteArrayOutputStream getGuiOutputStream() {
		return guiOutputStream;
	}

	public abstract String getConfigurationLogString(String fileName, String sqlStatement);

	protected abstract String getFileExtension();

	protected abstract void openWriter(OutputStream outputStream) throws Exception;

	protected abstract void startOutput(Connection connection, String sqlStatement, List<String> columnNames) throws Exception;

	protected abstract void startTableLine() throws Exception;

	protected abstract void writeColumn(String columnName, Object value) throws Exception;

	protected abstract void writeDateColumn(String columnName, LocalDate value) throws Exception;

	protected abstract void writeDateTimeColumn(String columnName, LocalDateTime value) throws Exception;

	protected abstract void writeDateTimeColumn(String columnName, ZonedDateTime value) throws Exception;

	protected abstract void endTableLine() throws Exception;

	protected abstract void endOutput() throws Exception;

	protected abstract void closeWriter() throws Exception;
}
