/*
Copyright (c) 2011 James Ahlborn

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.healthmarketscience.jackcess.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.healthmarketscience.jackcess.ColumnBuilder;
import com.healthmarketscience.jackcess.DataType;
import com.healthmarketscience.jackcess.IndexBuilder;

/**
 * Helper class used to maintain state during table creation.
 *
 * @author James Ahlborn
 * @usage _advanced_class_
 */
class TableCreator extends DBMutator
{
  private final String _name;
  private final List<ColumnBuilder> _columns;
  private final List<IndexBuilder> _indexes;
  private final Map<IndexBuilder,IndexState> _indexStates = 
    new IdentityHashMap<IndexBuilder,IndexState>();
  private final Map<ColumnBuilder,ColumnState> _columnStates = 
    new IdentityHashMap<ColumnBuilder,ColumnState>();
  private final List<ColumnBuilder> _lvalCols = new ArrayList<ColumnBuilder>();
  private int _tdefPageNumber = PageChannel.INVALID_PAGE_NUMBER;
  private int _umapPageNumber = PageChannel.INVALID_PAGE_NUMBER;
  private int _indexCount;
  private int _logicalIndexCount;

  public TableCreator(DatabaseImpl database, String name, 
                      List<ColumnBuilder> columns, List<IndexBuilder> indexes) {
    super(database);
    _name = name;
    _columns = columns;
    _indexes = ((indexes != null) ? indexes : 
                Collections.<IndexBuilder>emptyList());
  }

  public String getName() {
    return _name;
  }

  public int getTdefPageNumber() {
    return _tdefPageNumber;
  }

  public int getUmapPageNumber() {
    return _umapPageNumber;
  }

  public List<ColumnBuilder> getColumns() {
    return _columns;
  }

  public List<IndexBuilder> getIndexes() {
    return _indexes;
  }

  public boolean hasIndexes() {
    return !_indexes.isEmpty();
  }

  public int getIndexCount() {
    return _indexCount;
  }

  public int getLogicalIndexCount() {
    return _logicalIndexCount;
  }

  public IndexState getIndexState(IndexBuilder idx) {
    return _indexStates.get(idx);
  }

  public ColumnState getColumnState(ColumnBuilder col) {
    return _columnStates.get(col);
  }

  public List<ColumnBuilder> getLongValueColumns() {
    return _lvalCols;
  }

  /**
   * @return The number of variable length columns which are not long values
   *         found in the list
   * @usage _advanced_method_
   */
  public short countNonLongVariableLength() {
    short rtn = 0;
    for (ColumnBuilder col : _columns) {
      if (col.isVariableLength() && !col.getType().isLongValue()) {
        rtn++;
      }
    }
    return rtn;
  }
  

  /**
   * Creates the table in the database.
   * @usage _advanced_method_
   */
  public void createTable() throws IOException {

    validate();

    // assign column numbers and do some assorted column bookkeeping
    short columnNumber = (short) 0;
    for(ColumnBuilder col : _columns) {
      col.setColumnNumber(columnNumber++);
      if(col.getType().isLongValue()) {
        _lvalCols.add(col);
        // only lval columns need extra state
        _columnStates.put(col, new ColumnState());
      }
    }

    if(hasIndexes()) {
      // sort out index numbers.  for now, these values will always match
      // (until we support writing foreign key indexes)
      for(IndexBuilder idx : _indexes) {
        IndexState idxState = new IndexState();
        idxState.setIndexNumber(_logicalIndexCount++);
        idxState.setIndexDataNumber(_indexCount++);
        _indexStates.put(idx, idxState);
      }
    }

    getPageChannel().startWrite();
    try {
      
      // reserve some pages
      _tdefPageNumber = reservePageNumber();
      _umapPageNumber = reservePageNumber();
    
      //Write the tdef page to disk.
      TableImpl.writeTableDefinition(this);

      // update the database with the new table info
      getDatabase().addNewTable(_name, _tdefPageNumber, DatabaseImpl.TYPE_TABLE, 
                                null, null);

    } finally {
      getPageChannel().finishWrite();
    }
  }

  /**
   * Validates the new table information before attempting creation.
   */
  private void validate() {

    DatabaseImpl.validateIdentifierName(
        _name, getFormat().MAX_TABLE_NAME_LENGTH, "table");
    
    if((_columns == null) || _columns.isEmpty()) {
      throw new IllegalArgumentException(
          "Cannot create table with no columns");
    }
    if(_columns.size() > getFormat().MAX_COLUMNS_PER_TABLE) {
      throw new IllegalArgumentException(
          "Cannot create table with more than " +
          getFormat().MAX_COLUMNS_PER_TABLE + " columns");
    }
    
    Set<String> colNames = new HashSet<String>();
    // next, validate the column definitions
    for(ColumnBuilder column : _columns) {
      validateColumn(colNames, column);
    }

    List<ColumnBuilder> autoCols = getAutoNumberColumns();
    if(autoCols.size() > 1) {
      // for most autonumber types, we can only have one of each type
      Set<DataType> autoTypes = EnumSet.noneOf(DataType.class);
      for(ColumnBuilder c : autoCols) {
        validateAutoNumberColumn(autoTypes, c);
      }
    }

    if(hasIndexes()) {

      if(_indexes.size() > getFormat().MAX_INDEXES_PER_TABLE) {
        throw new IllegalArgumentException(
            "Cannot create table with more than " +
            getFormat().MAX_INDEXES_PER_TABLE + " indexes");
      }

      // now, validate the indexes
      Set<String> idxNames = new HashSet<String>();
      boolean foundPk = false;
      for(IndexBuilder index : _indexes) {
        index.validate(colNames, getFormat());
        if(!idxNames.add(index.getName().toUpperCase())) {
          throw new IllegalArgumentException("duplicate index name: " +
                                             index.getName());
        }
        if(index.isPrimaryKey()) {
          if(foundPk) {
            throw new IllegalArgumentException(
                "found second primary key index: " + index.getName());
          }
          foundPk = true;
        }
      }
    }
  }

  private List<ColumnBuilder> getAutoNumberColumns() 
  {
    List<ColumnBuilder> autoCols = new ArrayList<ColumnBuilder>(1);
    for(ColumnBuilder c : _columns) {
      if(c.isAutoNumber()) {
        autoCols.add(c);
      }
    }
    return autoCols;
  }

  /**
   * Maintains additional state used during index creation.
   * @usage _advanced_class_
   */
  static final class IndexState
  {
    private int _indexNumber;
    private int _indexDataNumber;
    private byte _umapRowNumber;
    private int _umapPageNumber;
    private int _rootPageNumber;

    public int getIndexNumber() {
      return _indexNumber;
    }

    public void setIndexNumber(int newIndexNumber) {
      _indexNumber = newIndexNumber;
    }

    public int getIndexDataNumber() {
      return _indexDataNumber;
    }

    public void setIndexDataNumber(int newIndexDataNumber) {
      _indexDataNumber = newIndexDataNumber;
    }

    public byte getUmapRowNumber() {
      return _umapRowNumber;
    }

    public void setUmapRowNumber(byte newUmapRowNumber) {
      _umapRowNumber = newUmapRowNumber;
    }

    public int getUmapPageNumber() {
      return _umapPageNumber;
    }

    public void setUmapPageNumber(int newUmapPageNumber) {
      _umapPageNumber = newUmapPageNumber;
    }

    public int getRootPageNumber() {
      return _rootPageNumber;
    }

    public void setRootPageNumber(int newRootPageNumber) {
      _rootPageNumber = newRootPageNumber;
    }
  }
    
}
