package com.intellij.util.io;

import gnu.trove.TIntObjectHashMap;

/**
* Created by IntelliJ IDEA.
* User: maximmossienko
* Date: 7/12/11
* Time: 1:34 PM
*/
abstract class IntToIntBtree {
  static final boolean doSanityCheck = false;
  static final boolean doDump = false;

  private final ResizeableMappedFile storage;
  final int pageSize;
  private final short maxInteriorNodes;
  final BtreeIndexNodeView root;
  private int maxStepsSearched;
  private int pagesCount;
  private int size;

  private final byte[] buffer;
  private final byte[] typedBuffer = new byte[8];
  // TODO limit it
  private final TIntObjectHashMap<byte[]> pagesCache = new TIntObjectHashMap<byte[]>(100);
  private boolean isLarge = true;

  public IntToIntBtree(int _pageSize, ResizeableMappedFile _storage, int rootAdress) {
    pageSize = _pageSize;
    buffer = new byte[_pageSize];
    storage = _storage;
    root = new BtreeIndexNodeView(this);
    root.setAddress(rootAdress);

    int i = (pageSize - BtreePage.META_PAGE_LEN) / BtreeIndexNodeView.INTERIOR_SIZE - 1;
    assert i < Short.MAX_VALUE && i % 2 == 0;
    maxInteriorNodes = (short)i;
    pagesCount = 1;
  }

  protected abstract int allocPage();

  private int nextPage() {
    int address = allocPage();
    ++pagesCount;
    return address;
  }

  protected static final int NULL_ID = 0;

  public int get(int key) {
    BtreeIndexNodeView currentIndexNode = new BtreeIndexNodeView(this);
    currentIndexNode.setAddress(root.address);
    int index = currentIndexNode.locate(key, false);

    if (index < 0) return NULL_ID;
    return currentIndexNode.addressAt(index);
  }

  public void put(int key, int value) {
    if (value == 0) throw new UnsupportedOperationException("Zero value is not supported");
    BtreeIndexNodeView currentIndexNode = new BtreeIndexNodeView(this);
    currentIndexNode.setAddress(root.address);
    int index = currentIndexNode.locate(key, true);

    if (index < 0) {
      ++size;
      currentIndexNode.insert(key, value, -index - 1);
    } else {
      currentIndexNode.setAddressAt(index, value);
    }
  }


  public int remove(int key) {
    // TODO
    //BtreeIndexNodeView currentIndexNode = new BtreeIndexNodeView(this);
    //currentIndexNode.setAddress(root.address);
    //int index = currentIndexNode.locate(key, false);
    throw new UnsupportedOperationException();
  }

  void setRootAddress(int newRootAddress) {
    root.setAddress(newRootAddress);
  }

  public int getMaxStepsSearched() {
    return maxStepsSearched;
  }

  public void setMaxStepsSearched(int _maxStepsSearched) {
    maxStepsSearched = _maxStepsSearched;
  }

  static void myAssert(boolean b) {
    if (!b) {
      int a = 1;
    }
    assert b;
  }

  static class BtreePage {
    protected final IntToIntBtree btree;
    protected int address;
    private short myChildrenCount;
    private byte[] buffer;

    // TODO link pages of the same height

    public BtreePage(IntToIntBtree btree) {
      this.btree = btree;
      myChildrenCount = -1;
    }

    void setAddress(int _address) {
      if (doSanityCheck) myAssert(_address % btree.pageSize == 0);
      address = _address;
      myChildrenCount = -1;
      buffer = null;
    }

    final short getChildrenCount() {
      if (myChildrenCount == -1) {
        myChildrenCount = (short)(((getByte(address + 1) & 0xFF) << 8) + (getByte(address + 2) & 0xFF));
      }
      return myChildrenCount;
    }

    final void setChildrenCount(short value) {
      myChildrenCount = value;
      putByte(address + 1, (byte)((value >> 8) & 0xFF));
      putByte(address + 2, (byte)(value & 0xFF));
    }

    protected final int getInt(int address) {
      if (alternative) {
        getBytes(address, btree.typedBuffer, 0, 4);
        return Bits.getInt(btree.typedBuffer, 0);
      } else {
        return btree.storage.getInt(address);
      }
    }

    protected final void putInt(int offset, int value) {
      if (alternative) {
        Bits.putInt(btree.typedBuffer, 0, value);
        putBytes(offset, btree.typedBuffer, 0, 4);
      } else {
        btree.storage.putInt(offset, value);
      }
    }

    protected final byte getByte(int address) {
      if (alternative) {
        getBytes(address, btree.typedBuffer, 0, 1);
        return btree.typedBuffer[0];
      } else {
        return btree.storage.get(address);
      }
    }

    protected final void putByte(int address, byte b) {
      if (alternative) {
        btree.typedBuffer[0] = b;
        putBytes(address, btree.typedBuffer, 0, 1);
      } else {
        btree.storage.put(address, b);
      }
    }

    static final boolean alternative = true;

    protected final void getBytes(int address, byte[] dst, int offset, int length) {
      if (alternative) {
        if (this.buffer == null) load();

        int base = address - this.address;
        for(int i = 0; i < length; ++i) {
          dst[offset + i] = this.buffer[base + i];
        }
      } else {
        btree.storage.get(address, dst, offset, length);
      }
    }

    private void load() {
      byte[] bytes = btree.pagesCache.get(address);
      if (bytes != null) {
        buffer = bytes;
      } else {
        buffer = new byte[btree.pageSize];
        btree.storage.get(this.address, this.buffer, 0, btree.pageSize);
        btree.pagesCache.put(address, buffer);
      }
    }

    protected final void putBytes(int address, byte[] src, int offset, int length) {
      if (alternative) {
        if (buffer == null) load();
        int base = address - this.address;
        for(int i = 0; i < length; ++i) {
          this.buffer[base + i] = src[offset + i];
        }
      } else {
        btree.storage.put(address, src, offset, length);
      }
    }

    void sync() {
      if (alternative) {
        if (buffer != null) {
          // TODO this is still too often, fix it
          btree.storage.put(address, buffer, 0, buffer.length);
          btree.pagesCache.put(address, buffer);
        }
      }
    }

    static int META_PAGE_LEN = 8;
  }

  // Leaf index node
  // (value_address {<0 if address in duplicates segment}, hash key) {getChildrenCount()}
  // (|next_node {<0} , hash key|) {getChildrenCount()} , next_node {<0}
  // next_node[i] is pointer to all less than hash_key[i] except for the last
  static class BtreeIndexNodeView extends BtreePage {
    private boolean isIndexLeaf;
    private boolean isIndexLeafSet;

    BtreeIndexNodeView(IntToIntBtree btree) {
      super(btree);
    }

    @Override
    void setAddress(int _address) {
      super.setAddress(_address);
      isIndexLeafSet = false;
    }

    static int INTERIOR_SIZE = 8;

    // binary search with negative result means insertion index
    int search(int value) {
      int hi = getChildrenCount() - 1;
      int lo = 0;

      while(lo <= hi) {
        int mid = lo + (hi - lo) / 2;
        int keyAtMid = keyAt(mid);

        if (value > keyAtMid) {
          lo = mid + 1;
        } else if (value < keyAtMid) {
          hi = mid - 1;
        } else {
          return mid;
        }
      }
      return -(lo + 1);
    }

    private int addressAt(int i) {
      if (doSanityCheck) {
        short childrenCount = getChildrenCount();
        myAssert(i < childrenCount || (!isIndexLeaf() && i == childrenCount));
      }
      return getInt(indexToOffset(i));
    }

    private void setAddressAt(int i, int value) {
      int offset = indexToOffset(i);
      if (doSanityCheck) {
        short childrenCount = getChildrenCount();
        myAssert(i < childrenCount || (!isIndexLeaf() && i == childrenCount));
        myAssert(offset + 4 < address + btree.pageSize);
        myAssert(offset >= address + META_PAGE_LEN);
      }
      putInt(offset, value);
    }

    private final int indexToOffset(int i) {
      return address + i * INTERIOR_SIZE + META_PAGE_LEN;
    }

    private int keyAt(int i) {
      if (doSanityCheck) myAssert(i < getChildrenCount());
      return getInt(indexToOffset(i) + 4);
    }

    private void setKeyAt(int i, int value) {
      int offset = indexToOffset(i) + 4;
      if (doSanityCheck) {
        myAssert(i < getChildrenCount());
        myAssert(offset + 4 < address + btree.pageSize);
        myAssert(offset >= address + META_PAGE_LEN);
      }
      putInt(offset, value);
    }

    static int INDEX_LEAF_MASK = 0x1;

    boolean isIndexLeaf() {
      if (!isIndexLeafSet) {
        byte b = getByte(address);
        isIndexLeaf = (b & INDEX_LEAF_MASK) == INDEX_LEAF_MASK;
        isIndexLeafSet = true;
      }
      return isIndexLeaf;
    }

    void setIndexLeaf(boolean value) {
      isIndexLeaf = value;
      byte b = getByte(address);
      if (value) b |= INDEX_LEAF_MASK;
      else b &= ~INDEX_LEAF_MASK;
      putByte(address, b);
    }

    final short getMaxChildrenCount() {
      return btree.maxInteriorNodes;
    }

    final boolean isFull() {
      short childrenCount = getChildrenCount();
      if (!isIndexLeaf()) {
        ++childrenCount;
      }
      return childrenCount == getMaxChildrenCount();
    }

    private int splitNode(int parentAddress) {
      if (doSanityCheck) {
        myAssert(isFull());
        dump("before split:"+isIndexLeaf());
      }

      short maxIndex = (short)(getMaxChildrenCount() / 2);

      BtreeIndexNodeView newIndexNode = new BtreeIndexNodeView(btree);
      newIndexNode.setAddress(btree.nextPage());

      boolean indexLeaf = isIndexLeaf();
      newIndexNode.setIndexLeaf(indexLeaf);

      short recordCount = getChildrenCount();

      short recordCountInNewNode = (short)(recordCount - maxIndex);
      newIndexNode.setChildrenCount(recordCountInNewNode);
      int medianKey;

      if (btree.isLarge) {
        final int bytesToMove = recordCountInNewNode * INTERIOR_SIZE;
        getBytes(indexToOffset(maxIndex), btree.buffer, 0, bytesToMove);
        newIndexNode.putBytes(newIndexNode.indexToOffset(0), btree.buffer, 0, bytesToMove);
      } else {
        for(int i = 0; i < recordCountInNewNode; ++i) {
          newIndexNode.setAddressAt(i, addressAt(i + maxIndex));
          newIndexNode.setKeyAt(i, keyAt(i + maxIndex));
        }
      }

      if (indexLeaf) {
        medianKey = newIndexNode.keyAt(0);
      } else {
        newIndexNode.setAddressAt(recordCountInNewNode, addressAt(recordCount));
        --maxIndex;
        medianKey = keyAt(maxIndex);     // key count is odd (since children count is even) and middle key goes to parent
      }

      setChildrenCount(maxIndex);

      if (parentAddress != 0) {
        BtreeIndexNodeView parent = new BtreeIndexNodeView(btree);
        parent.setAddress(parentAddress);

        if (doSanityCheck) {
          int medianKeyInParent = parent.search(medianKey);
          int ourKey = keyAt(0);
          int ourKeyInParent = parent.search(ourKey);
          parent.dump("About to insert "+medianKey + "," + newIndexNode.address+"," + medianKeyInParent + " our key " + ourKey + ", " + ourKeyInParent);

          myAssert(medianKeyInParent < 0);
          myAssert(!parent.isFull());
        }

        parent.insert(medianKey, -newIndexNode.address);

        if (doSanityCheck) {
          parent.dump("After modifying parent");
          int search = parent.search(medianKey);
          myAssert(search >= 0);
          myAssert(parent.addressAt(search + 1) == -newIndexNode.address);

          dump("old node after split:");
          newIndexNode.dump("new node after split:");
        }
      } else {
        if (doSanityCheck) {
          btree.root.dump("Splitting root:"+medianKey);
        }
        int newRootAddress = btree.nextPage();
        if (doSanityCheck) {
          System.out.println("Pages:"+btree.pagesCount+", elements:"+btree.size + ", average:" + (btree.maxStepsSearched + 1));
        }
        btree.setRootAddress(newRootAddress);
        parentAddress = newRootAddress;
        btree.root.setChildrenCount((short)1);
        btree.root.setKeyAt(0, medianKey);
        btree.root.setAddressAt(0, -address);
        btree.root.setAddressAt(1, -newIndexNode.address);
        btree.root.sync();

        if (doSanityCheck) {
          btree.root.dump("New root");
          dump("First child");
          newIndexNode.dump("Second child");
        }
      }

      sync();
      newIndexNode.sync();

      return parentAddress;
    }

    private void dump(String s) {
      if (!doDump) return;
      short maxIndex = getChildrenCount();
      System.out.println(s + " @" + address);
      for(int i = 0; i < maxIndex; ++i) {
        System.out.print(addressAt(i) + " " + keyAt(i) + " ");
      }

      if (!isIndexLeaf()) {
        System.out.println(addressAt(maxIndex));
      }
      else {
        System.out.println();
      }
    }

    private int locate(int valueHC, boolean split) {
      int searched = 0;
      int parentAddress = 0;

      while(true) {
        if (split && isFull()) {
          parentAddress = splitNode(parentAddress);
          setAddress(parentAddress);
          --searched;
        }

        int i = search(valueHC);

        ++searched;

        if (isIndexLeaf()) {
          btree.maxStepsSearched = Math.max(btree.maxStepsSearched, searched);
          return i;
        }

        int address = i < 0 ? addressAt(-i - 1):addressAt(i + 1);
        if (doSanityCheck) myAssert(address != 0);
        parentAddress = this.address;
        setAddress(-address);
      }
    }

    private void insert(int valueHC, int newValueId) {
      int medianKeyInParent = search(valueHC);
      myAssert(medianKeyInParent < 0);
      insert(valueHC, newValueId, -medianKeyInParent - 1);
    }

    private void insert(int valueHC, int newValueId, int index) {
      if (doSanityCheck) myAssert(!isFull());
      insertToNotFull(valueHC, newValueId, index);
    }

    private void insertToNotFull(int valueHC, int newValueId, int index) {
      short recordCount = getChildrenCount();
      if (doSanityCheck) myAssert(recordCount < getMaxChildrenCount());
      setChildrenCount((short)(recordCount + 1));

      // TODO Clever books tell us to use Btree for cheaper elements shifting within page
      if (isIndexLeaf()) {
        if (btree.isLarge) {
          final int bytesToMove = (recordCount - index) * INTERIOR_SIZE;
          getBytes(indexToOffset(index), btree.buffer, 0, bytesToMove);
          putBytes(indexToOffset(index + 1), btree.buffer, 0, bytesToMove);
        } else {
          for(int i = recordCount - 1; i >= index; --i) {
            setKeyAt(i + 1, keyAt(i));
            setAddressAt(i + 1, addressAt(i));
          }
        }
        setKeyAt(index, valueHC);
        setAddressAt(index, newValueId);
      } else {
        // <address> (<key><address>) {record_count - 1}
        //
        setAddressAt(recordCount + 1, addressAt(recordCount));
        if (btree.isLarge) {
          int elementsAfterIndex = recordCount - index - 1;
          if (elementsAfterIndex > 0) {
            int bytesToMove = elementsAfterIndex * INTERIOR_SIZE;
            getBytes(indexToOffset(index + 1), btree.buffer, 0, bytesToMove);
            putBytes(indexToOffset(index + 2), btree.buffer, 0, bytesToMove);
          }
        } else {
          for(int i = recordCount - 1; i > index; --i) {
            setKeyAt(i + 1, keyAt(i));
            setAddressAt(i + 1, addressAt(i));
          }
        }

        if (index < recordCount) setKeyAt(index + 1, keyAt(index));

        setKeyAt(index, valueHC);
        setAddressAt(index + 1, newValueId);
      }

      if (doSanityCheck) {
        if (index > 0) myAssert(keyAt(index - 1) < keyAt(index));
        if (index < recordCount) myAssert(keyAt(index) < keyAt(index + 1));
      }

      sync();
    }
  }
}
