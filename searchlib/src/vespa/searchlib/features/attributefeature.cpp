// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include <vespa/fastos/fastos.h>
#include <vespa/log/log.h>
LOG_SETUP(".features.attributefeature");
#include "attributefeature.h"
#include "utils.h"
#include "valuefeature.h"

#include <vespa/searchcommon/common/undefinedvalues.h>
#include <vespa/searchcommon/attribute/attributecontent.h>
#include <vespa/searchlib/tensor/tensor_attribute.h>
#include <vespa/searchlib/features/constant_tensor_executor.h>
#include <vespa/searchlib/features/tensor_from_tensor_attribute_executor.h>
#include <vespa/searchlib/fef/fieldinfo.h>
#include <vespa/searchlib/fef/indexproperties.h>
#include <vespa/searchlib/attribute/singlenumericattribute.h>
#include <vespa/vespalib/eval/value_type.h>
#include <vespa/searchlib/fef/feature_type.h>

using search::attribute::IAttributeVector;
using search::attribute::CollectionType;
using search::attribute::BasicType;
using search::attribute::ConstCharContent;
using search::attribute::IntegerContent;
using search::attribute::FloatContent;
using search::attribute::TensorAttribute;
using search::attribute::WeightedConstCharContent;
using search::attribute::WeightedIntegerContent;
using search::attribute::WeightedFloatContent;
using search::fef::FeatureExecutor;
using search::features::util::ConstCharPtr;
using vespalib::eval::ValueType;
using search::fef::FeatureType;

using namespace search::fef::indexproperties;

namespace {
template <typename X, typename Y>
bool equals(const X & lhs, const Y & rhs) {
    return lhs == rhs;
}

template <>
bool equals<ConstCharPtr, vespalib::stringref>(const ConstCharPtr & lhs, const vespalib::stringref & rhs) {
    return strcmp(lhs, rhs.c_str()) == 0;
}

template <typename T>
bool
isUndefined(const T & value, const BasicType::Type & type)
{
    switch (type) {
    case BasicType::INT8:
        return search::attribute::isUndefined<int8_t>(static_cast<int8_t>(value));
    case BasicType::INT16:
        return search::attribute::isUndefined<int16_t>(static_cast<int16_t>(value));
    case BasicType::INT32:
        return search::attribute::isUndefined<int32_t>(static_cast<int32_t>(value));
    case BasicType::INT64:
        return search::attribute::isUndefined<int64_t>(static_cast<int64_t>(value));
    case BasicType::FLOAT:
        return search::attribute::isUndefined<float>(static_cast<float>(value));
    case BasicType::DOUBLE:
        return search::attribute::isUndefined<double>(static_cast<double>(value));
    default:
        return false;
    }
}

template <>
bool
isUndefined<vespalib::stringref>(const vespalib::stringref &, const BasicType::Type &)
{
    return false;
}

template <typename T>
search::feature_t
considerUndefined(const T & value, const BasicType::Type & type)
{
    if (isUndefined(value, type)) {
        return search::attribute::getUndefined<search::feature_t>();
    }
    return search::features::util::getAsFeature(value);
}

template <>
search::feature_t
considerUndefined<ConstCharPtr>(const ConstCharPtr & value, const BasicType::Type &)
{
    return search::features::util::getAsFeature(value);
}


}


namespace search {
namespace features {

/**
 * Implements the executor for fetching values from a single or array attribute vector
 */
template <typename T>
class SingleAttributeExecutor : public fef::FeatureExecutor {
private:
    const T & _attribute;

public:
    /**
     * Constructs an executor.
     *
     * @param attribute The attribute vector to use.
     */
    SingleAttributeExecutor(const T & attribute) : _attribute(attribute) { }

    // Inherit doc from FeatureExecutor.
    virtual void execute(search::fef::MatchData & data);
};

class CountOnlyAttributeExecutor : public fef::FeatureExecutor {
private:
    const attribute::IAttributeVector & _attribute;

public:
    /**
     * Constructs an executor.
     *
     * @param attribute The attribute vector to use.
     */
    CountOnlyAttributeExecutor(const attribute::IAttributeVector & attribute) : _attribute(attribute) { }

    // Inherit doc from FeatureExecutor.
    virtual void execute(search::fef::MatchData & data);
};
/**
 * Implements the executor for fetching values from a single or array attribute vector
 */
template <typename T>
class AttributeExecutor : public fef::FeatureExecutor {
private:
    const attribute::IAttributeVector * _attribute;
    attribute::BasicType::Type _attrType;
    uint32_t _idx;
    T _buffer; // used when fetching values from the attribute
    feature_t _defaultCount;

public:
    /**
     * Constructs an executor.
     *
     * @param attribute The attribute vector to use.
     * @param idx       The index used for an array attribute.
     */
    AttributeExecutor(const search::attribute::IAttributeVector * attribute, uint32_t idx);

    // Inherit doc from FeatureExecutor.
    virtual void execute(search::fef::MatchData & data);
};


/**
 * Implements the executor for fetching weights from a weighted set attribute
 */
template <typename BT, typename T>
class WeightedSetAttributeExecutor : public fef::FeatureExecutor {
private:
    const attribute::IAttributeVector * _attribute;
    attribute::BasicType::Type _attrType;
    BT   _buffer; // used when fetching values and weights from the attribute
    T    _key;    // the key to find a weight for
    bool _useKey;

public:
    /**
     * Constructs an executor.
     *
     * @param attribue The attribute vector to use.
     * @param key      The key to find a corresponding weight for.
     * @param useKey   Whether we should consider the key.
     */
    WeightedSetAttributeExecutor(const search::attribute::IAttributeVector * attribute, T key, bool useKey);

    // Inherit doc from FeatureExecutor.
    virtual void execute(search::fef::MatchData & data);
};

template <typename T>
void
SingleAttributeExecutor<T>::execute(search::fef::MatchData & match)
{
    typename T::LoadedValueType v = _attribute.getFast(match.getDocId());
    // value
    *match.resolveFeature(outputs()[0]) = __builtin_expect(attribute::isUndefined(v), false)
                                          ? attribute::getUndefined<search::feature_t>()
                                          : util::getAsFeature(v);
    *match.resolveFeature(outputs()[1]) = 0.0f;  // weight
    *match.resolveFeature(outputs()[2]) = 0.0f;  // contains
    *match.resolveFeature(outputs()[3]) = 1.0f; // count
}

void
CountOnlyAttributeExecutor::execute(search::fef::MatchData & match)
{
    *match.resolveFeature(outputs()[0]) = 0.0f;  // value
    *match.resolveFeature(outputs()[1]) = 0.0f;  // weight
    *match.resolveFeature(outputs()[2]) = 0.0f;  // contains
    *match.resolveFeature(outputs()[3]) = _attribute.getValueCount(match.getDocId()); // count
}

template <typename T>
AttributeExecutor<T>::AttributeExecutor(const IAttributeVector * attribute, uint32_t idx) :
    fef::FeatureExecutor(),
    _attribute(attribute),
    _attrType(attribute->getBasicType()),
    _idx(idx),
    _buffer(),
    _defaultCount((attribute->getCollectionType() == CollectionType::ARRAY) ? 0 : 1)
{
    _buffer.allocate(_attribute->getMaxValueCount());
}

template <typename T>
void
AttributeExecutor<T>::execute(search::fef::MatchData & match)
{
    feature_t value = 0.0f;
    _buffer.fill(*_attribute, match.getDocId());
    if (_idx < _buffer.size()) {
        value = considerUndefined(_buffer[_idx], _attrType);
    }
    *match.resolveFeature(outputs()[0]) = value; // value
    *match.resolveFeature(outputs()[1]) = 0.0f;  // weight
    *match.resolveFeature(outputs()[2]) = 0.0f;  // contains
    *match.resolveFeature(outputs()[3]) = _defaultCount; // count
}


template <typename BT, typename T>
WeightedSetAttributeExecutor<BT, T>::WeightedSetAttributeExecutor(const IAttributeVector * attribute, T key, bool useKey) :
    fef::FeatureExecutor(),
    _attribute(attribute),
    _attrType(attribute->getBasicType()),
    _buffer(),
    _key(key),
    _useKey(useKey)
{
}

template <typename BT, typename T>
void
WeightedSetAttributeExecutor<BT, T>::execute(search::fef::MatchData & match)
{
    feature_t value = 0.0f;
    feature_t weight = 0.0f;
    feature_t contains = 0.0f;
    feature_t count = 0.0f;
    if (_useKey) {
        _buffer.fill(*_attribute, match.getDocId());
        for (uint32_t i = 0; i < _buffer.size(); ++i) {
            if (equals(_buffer[i].getValue(), _key)) {
                value = considerUndefined(_key, _attrType);
                weight = static_cast<feature_t>(_buffer[i].getWeight());
                contains = 1.0f;
                break;
            }
        }
    } else {
        count = _attribute->getValueCount(match.getDocId());
    }
    *match.resolveFeature(outputs()[0]) = value;  // value
    *match.resolveFeature(outputs()[1]) = weight; // weight
    *match.resolveFeature(outputs()[2]) = contains; // contains
    *match.resolveFeature(outputs()[3]) = count; // count
}


AttributeBlueprint::AttributeBlueprint() :
    search::fef::Blueprint("attribute"),
    _attrName(),
    _extra(),
    _tensorType(ValueType::double_type())
{
}

void
AttributeBlueprint::visitDumpFeatures(const search::fef::IIndexEnvironment &,
                                      search::fef::IDumpFeatureVisitor &) const
{
}

bool
AttributeBlueprint::setup(const search::fef::IIndexEnvironment & env,
                          const search::fef::ParameterList & params)
{
    // params[0] = attribute name
    // params[1] = index (array attribute) or key (weighted set attribute)
    _attrName = params[0].getValue();
    if (params.size() == 2) {
        _extra = params[1].getValue();
    }
    vespalib::string attrType = type::Attribute::lookup(env.getProperties(), _attrName);
    if (!attrType.empty()) {
        _tensorType = ValueType::from_spec(attrType);
    }
    FeatureType output_type = _tensorType.is_tensor()
                              ? FeatureType::object(_tensorType)
                              : FeatureType::number();
    describeOutput("value", "The value of a single value attribute, "
                   "the value at the given index of an array attribute, "
                   "the given key of a weighted set attribute, or"
                   "the tensor of a tensor attribute", output_type);
    if (!_tensorType.is_tensor()) {
        describeOutput("weight", "The weight associated with the given key in a weighted set attribute.");
        describeOutput("contains", "1 if the given key is present in a weighted set attribute, 0 otherwise.");
        describeOutput("count", "Returns the number of elements in this array or weighted set attribute.");
    }
    env.hintAttributeAccess(_attrName);
    return true;
}

search::fef::Blueprint::UP
AttributeBlueprint::createInstance() const
{
    return search::fef::Blueprint::UP(new AttributeBlueprint());
}

#define CREATE_AND_RETURN_IF_SINGLE_NUMERIC(a, T) \
    if (dynamic_cast<const SingleValueNumericAttribute<T> *>(a) != NULL) { \
        return FeatureExecutor::LP(new SingleAttributeExecutor<SingleValueNumericAttribute<T>>(*static_cast<const SingleValueNumericAttribute<T> *>(a))); \
    }

namespace {

search::fef::FeatureExecutor::LP
createAttributeExecutor(const IAttributeVector *attribute, const vespalib::string &attrName, const vespalib::string &extraParam)
{
    if (attribute == NULL) {
        LOG(warning, "The attribute vector '%s' was not found in the attribute manager, returning default values.",
                attrName.c_str());
        std::vector<feature_t> values(4, 0.0f);
        return FeatureExecutor::LP(new ValueExecutor(values));
    }
    if (attribute->getCollectionType() == CollectionType::WSET) {
        bool useKey = !extraParam.empty();
        if (useKey) {
            if (attribute->isStringType()) {
                return FeatureExecutor::LP
                    (new WeightedSetAttributeExecutor<WeightedConstCharContent, vespalib::stringref>(attribute, extraParam, useKey));
            } else if (attribute->isIntegerType()) {
                return FeatureExecutor::LP
                    (new WeightedSetAttributeExecutor<WeightedIntegerContent, int64_t>(attribute, util::strToNum<int64_t>(extraParam), useKey));
            } else { // FLOAT
                return FeatureExecutor::LP
                    (new WeightedSetAttributeExecutor<WeightedFloatContent, double>(attribute, util::strToNum<double>(extraParam), useKey));
            }
        } else {
            return FeatureExecutor::LP(new CountOnlyAttributeExecutor(*attribute));
        }
    } else { // SINGLE or ARRAY
        if ((attribute->getCollectionType() == CollectionType::SINGLE) && (attribute->isIntegerType() || attribute->isFloatingPointType())) {
            CREATE_AND_RETURN_IF_SINGLE_NUMERIC(attribute, FloatingPointAttributeTemplate<double>);
            CREATE_AND_RETURN_IF_SINGLE_NUMERIC(attribute, FloatingPointAttributeTemplate<float>);
            CREATE_AND_RETURN_IF_SINGLE_NUMERIC(attribute, IntegerAttributeTemplate<int32_t>);
            CREATE_AND_RETURN_IF_SINGLE_NUMERIC(attribute, IntegerAttributeTemplate<int64_t>);
        }
        {
            uint32_t idx = 0;
            if (!extraParam.empty()) {
                idx = util::strToNum<uint32_t>(extraParam);
            } else if (attribute->getCollectionType() == CollectionType::ARRAY) {
                return FeatureExecutor::LP(new CountOnlyAttributeExecutor(*attribute));
            }
            if (attribute->isStringType()) {
                return FeatureExecutor::LP(new AttributeExecutor<ConstCharContent>(attribute, idx));
            } else if (attribute->isIntegerType()) {
                return FeatureExecutor::LP(new AttributeExecutor<IntegerContent>(attribute, idx));
            } else { // FLOAT
                return FeatureExecutor::LP(new AttributeExecutor<FloatContent>(attribute, idx));
            }
        }
    }
}

search::fef::FeatureExecutor::LP
createTensorAttributeExecutor(const IAttributeVector *attribute, const vespalib::string &attrName,
                              const ValueType &tensorType)
{
    if (attribute == NULL) {
        LOG(warning, "The attribute vector '%s' was not found in the attribute manager."
                " Returning empty tensor.", attrName.c_str());
        return ConstantTensorExecutor::createEmpty(tensorType);
    }
    if (attribute->getCollectionType() != search::attribute::CollectionType::SINGLE ||
            attribute->getBasicType() != search::attribute::BasicType::TENSOR) {
        LOG(warning, "The attribute vector '%s' is NOT of type tensor."
                " Returning empty tensor.", attribute->getName().c_str());
        return ConstantTensorExecutor::createEmpty(tensorType);
    }
    const TensorAttribute *tensorAttribute = dynamic_cast<const TensorAttribute *>(attribute);
    if (tensorAttribute == nullptr) {
        LOG(warning, "The attribute vector '%s' could not be converted to a tensor attribute."
                " Returning empty tensor.", attribute->getName().c_str());
        return ConstantTensorExecutor::createEmpty(tensorType);
    }
    if (tensorType != tensorAttribute->getConfig().tensorType()) {
        LOG(warning, "The tensor attribute '%s' has tensor type '%s',"
                " while the feature executor expects type '%s'. Returning empty tensor.",
                tensorAttribute->getName().c_str(),
                tensorAttribute->getConfig().tensorType().to_spec().c_str(),
                tensorType.to_spec().c_str());
        return ConstantTensorExecutor::createEmpty(tensorType);
    }
    return FeatureExecutor::LP(new TensorFromTensorAttributeExecutor(tensorAttribute));
}

}

search::fef::FeatureExecutor::LP
AttributeBlueprint::createExecutor(const search::fef::IQueryEnvironment &env) const
{
    const IAttributeVector *attribute = env.getAttributeContext().getAttribute(_attrName);
    if (_tensorType.is_tensor()) {
        return createTensorAttributeExecutor(attribute, _attrName, _tensorType);
    } else {
        return createAttributeExecutor(attribute, _attrName, _extra);
    }
}

} // namespace features
} // namespace search
