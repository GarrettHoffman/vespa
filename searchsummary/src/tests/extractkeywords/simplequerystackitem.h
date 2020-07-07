// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchlib/query/weight.h>
#include <vespa/searchlib/util/rawbuf.h>
#include <vespa/vespalib/stllike/string.h>
#include <vespa/searchlib/parsequery/parse.h>

namespace search {

/**
 * An item on the simple query stack.
 *
 * An object of this class represents a single item
 * on the simple query stack. It has a type, which corresponds
 * to the different query stack execution operations. It also
 * provides an arity, and the string values indexName and term, to
 * accomodate the different needs of the operations.
 * It also includes a mechanism for making singly linked lists
 * with sub-lists. This is used during the parsing, and also
 * when constructing the simple query stack.
 */
class SimpleQueryStackItem : public ParseItem
{
private:
    SimpleQueryStackItem(const SimpleQueryStackItem &) = delete;
    SimpleQueryStackItem& operator=(const SimpleQueryStackItem &) = delete;
    SimpleQueryStackItem();
public:
    /** Pointer to next item in a linked list. */
    SimpleQueryStackItem *_next;
    /** Pointer to first item in a sublist. */
    SimpleQueryStackItem *_sibling;

private:
    query::Weight _weight;
    uint32_t      _uniqueId;
    uint32_t      _arg1;
    double        _arg2;
    double        _arg3;
    uint8_t       _type;
    uint8_t       _flags;

public:
    /** Extra information on each item (creator id) coded in bits 12-19 of _type */
    static inline ItemCreator GetCreator(uint8_t type) { return static_cast<ItemCreator>((type >> 3) & 0x01); }
    /** The old item type now uses only the lower 12 bits in a backward compatible way) */
    static inline ItemType GetType(uint8_t type) { return static_cast<ItemType>(type & 0x1F); }
    inline ItemType Type() const { return GetType(_type); }

    static inline bool GetFeature(uint8_t type, uint8_t feature)
    { return ((type & feature) != 0); }

    static inline bool GetFeature_Weight(uint8_t type)
    { return GetFeature(type, IF_WEIGHT); }

    static inline bool getFeature_UniqueId(uint8_t type)
    { return GetFeature(type, IF_UNIQUEID); }

    static inline bool getFeature_Flags(uint8_t type)
    { return GetFeature(type, IF_FLAGS); }

    inline bool Feature(uint8_t feature) const
    { return GetFeature(_type, feature); }

    inline bool Feature_Weight() const
    { return GetFeature_Weight(_type); }

    inline bool feature_UniqueId() const
    { return getFeature_UniqueId(_type); }

    inline bool feature_Flags() const
    { return getFeature_Flags(_type); }

    static inline bool getFlag(uint8_t flags, uint8_t flag)
    { return ((flags & flag) != 0); }

    /** The number of operands for the operation. */
    uint32_t _arity;
    /** The name of the specified index, or NULL if no index. */
    vespalib::string _indexName;
    /** The specified search term. */
    vespalib::string  _term;

/**
 * Overloaded constructor for SimpleQueryStackItem. Used primarily for
 * the operators, or pharse without indexName.
 *
 * @param type The type of the SimpleQueryStackItem.
 * @param arity The arity of the operation indicated by the SimpleQueryStackItem.
 */
    SimpleQueryStackItem(ItemType type, int arity);

/**
 * Overloaded constructor for SimpleQueryStackItem. Used for PHRASEs.
 *
 * @param type The type of the SimpleQueryStackItem.
 * @param arity The arity of the operation indicated by the SimpleQueryStackItem.
 * @param idx The name of the index of the SimpleQueryStackItem.
 */
    SimpleQueryStackItem(ItemType type, int arity, const char *index);

/**
 * Overloaded constructor for SimpleQueryStackItem. Used for TERMs without index.
 *
 * @param type The type of the SimpleQueryStackItem.
 * @param term The actual term string of the SimpleQueryStackItem.
 */
    SimpleQueryStackItem(ItemType type, const char *term);

/**
 * Destructor for SimpleQueryStackItem.
 */
    ~SimpleQueryStackItem();

/**
 * Set the value of the _term field.
 * @param term The string to set the _term field to.
 */
    void SetTerm(const char *term) { _term = term; }

/**
 * Set the value of the _indexName field.
 * @param idx The string to set the _indexName field to.
 */
    void SetIndex(const char *index) { _indexName = index; }

    /**
     * Set the type of the operator. Use this with caution,
     * as this changes the semantics of the item.
     *
     * @param type The new type.
     */
    void SetType(ItemType type) {
        _type = (_type & ~0x1F) | type;
    }

    /**
     * Get the unique id for this item.
     *
     * @return unique id for this item
     **/
    uint32_t getUniqueId() const { return _uniqueId; }

    /**
     * Encode the item in a binary buffer.
     * @param buf Pointer to a buffer containing the encoded contents.
     */
    void AppendBuffer(RawBuf *buf) const;
};

}
