/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License, Version 1.0 only
 * (the "License").  You may not use this file except in compliance
 * with the License.
 *
 * You can obtain a copy of the license at legal-notices/CDDLv1_0.txt
 * or http://forgerock.org/license/CDDLv1.0.html.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at legal-notices/CDDLv1_0.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information:
 *      Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 *
 *
 *      Copyright 2006-2010 Sun Microsystems, Inc.
 *      Portions Copyright 2014 ForgeRock AS
 */
package org.opends.server.backends.jeb;

import org.forgerock.i18n.slf4j.LocalizedLogger;
import org.opends.server.types.*;
import java.util.*;
import org.opends.server.api.SubstringMatchingRule;

/**
 * An implementation of an Indexer for attribute substrings.
 */
public class SubstringIndexer extends Indexer
{
  private static final LocalizedLogger logger = LocalizedLogger.getLoggerForThisClass();



  /**
   * The comparator for index keys generated by this class.
   */
  private static final Comparator<byte[]> comparator =
       new AttributeIndex.KeyComparator();

  /**
   * The attribute type for which this instance will
   * generate index keys.
   */
  private AttributeType attributeType;

  /**
   * The substring length.
   */
  private int substrLength;

  /**
   * Create a new attribute substring indexer for the given index configuration.
   * @param attributeType The attribute type for which an indexer is
   * required.
   * @param substringLength The decomposed substring length.
   */
  public SubstringIndexer(AttributeType attributeType, int substringLength)
  {
    this.attributeType = attributeType;
    this.substrLength = substringLength;
  }

  /**
   * Get a string representation of this object.  The returned value is
   * used to name an index created using this object.
   * @return A string representation of this object.
   */
  public String toString()
  {
    return attributeType.getNameOrOID() + ".substring";
  }

  /**
   * Get the comparator that must be used to compare index keys
   * generated by this class.
   *
   * @return A byte array comparator.
   */
  public Comparator<byte[]> getComparator()
  {
    return comparator;
  }

  /**
   * Generate the set of index keys for an entry.
   *
   * @param entry The entry.
   * @param keys The set into which the generated keys will be inserted.
   */
  public void indexEntry(Entry entry, Set<byte[]> keys)
  {
    List<Attribute> attrList =
         entry.getAttribute(attributeType);
    if (attrList != null)
    {
      indexAttribute(attrList, keys);
    }
  }

  /**
   * Generate the set of index keys to be added and the set of index keys
   * to be deleted for an entry that has been replaced.
   *
   * @param oldEntry The original entry contents.
   * @param newEntry The new entry contents.
   * @param modifiedKeys The map into which the modified keys will be inserted.
   */
  public void replaceEntry(Entry oldEntry, Entry newEntry,
                           Map<byte[], Boolean> modifiedKeys)
  {
    List<Attribute> newAttributes = newEntry.getAttribute(attributeType, true);
    List<Attribute> oldAttributes = oldEntry.getAttribute(attributeType, true);

    indexAttribute(oldAttributes, modifiedKeys, false);
    indexAttribute(newAttributes, modifiedKeys, true);
  }



  /**
   * Generate the set of index keys to be added and the set of index keys
   * to be deleted for an entry that was modified.
   *
   * @param oldEntry The original entry contents.
   * @param newEntry The new entry contents.
   * @param mods The set of modifications that were applied to the entry.
   * @param modifiedKeys The map into which the modified keys will be inserted.
   */
  public void modifyEntry(Entry oldEntry, Entry newEntry,
                          List<Modification> mods,
                          Map<byte[], Boolean> modifiedKeys)
  {
    List<Attribute> newAttributes = newEntry.getAttribute(attributeType, true);
    List<Attribute> oldAttributes = oldEntry.getAttribute(attributeType, true);

    indexAttribute(oldAttributes, modifiedKeys, false);
    indexAttribute(newAttributes, modifiedKeys, true);
  }



  /**
   * Generate the set of substring index keys for an attribute.
   * @param attrList The attribute for which substring keys are required.
   * @param keys The set into which the generated keys will be inserted.
   */
  private void indexAttribute(List<Attribute> attrList,
                              Set<byte[]> keys)
  {
    if (attrList == null) return;
    for (Attribute attr : attrList)
    {
      if (attr.isVirtual())
      {
        continue;
      }
      //Get the substring matching rule.
      SubstringMatchingRule rule =
              attr.getAttributeType().getSubstringMatchingRule();
      for (AttributeValue value : attr)
      {
        try
        {
          byte[] normalizedBytes = rule.normalizeAttributeValue(value.getValue()).
                  toByteArray();

          substringKeys(normalizedBytes, keys);
        }
        catch (DirectoryException e)
        {
          logger.traceException(e);
        }
      }
    }
  }

  /**
   * Decompose an attribute value into a set of substring index keys.
   * The ID of the entry containing this value should be inserted
   * into the list of each of these keys.
   *
   * @param value A byte array containing the normalized attribute value
   * @param set A set into which the keys will be inserted.
   */
  private void substringKeys(byte[] value, Set<byte[]> set)
  {
    byte[] keyBytes;

    // Example: The value is ABCDE and the substring length is 3.
    // We produce the keys ABC BCD CDE DE E
    // To find values containing a short substring such as DE,
    // iterate through keys with prefix DE. To find values
    // containing a longer substring such as BCDE, read keys
    // BCD and CDE.
    for (int i = 0, remain = value.length; remain > 0; i++, remain--)
    {
      int len = Math.min(substrLength, remain);
      keyBytes = makeSubstringKey(value, i, len);
      set.add(keyBytes);
    }
  }

  /**
   * Makes a byte array representing a substring index key for
   * one substring of a value.
   *
   * @param bytes The byte array containing the value
   * @param pos The starting position of the substring
   * @param len The length of the substring
   * @return A byte array containing a substring key
   */
  private byte[] makeSubstringKey(byte[] bytes, int pos, int len)
  {
    byte[] keyBytes = new byte[len];
    System.arraycopy(bytes, pos, keyBytes, 0, len);
    return keyBytes;
  }

  /**
   * Generate the set of index keys for an attribute.
   * @param attrList The attribute to be indexed.
   * @param modifiedKeys The map into which the modified
   * keys will be inserted.
   * @param insert <code>true</code> if generated keys should
   * be inserted or <code>false</code> otherwise.
   */
  private void indexAttribute(List<Attribute> attrList,
                              Map<byte[], Boolean> modifiedKeys,
                              Boolean insert)
  {
    if (attrList == null) return;

    for (Attribute attr : attrList)
    {
      if (attr.isVirtual())
      {
        continue;
      }
            //Get the substring matching rule.
      SubstringMatchingRule rule =
              attr.getAttributeType().getSubstringMatchingRule();

      for (AttributeValue value : attr)
      {
        try
        {
          byte[] normalizedBytes = rule.normalizeAttributeValue(value.getValue())
                  .toByteArray();

          substringKeys(normalizedBytes, modifiedKeys, insert);
        }
        catch (DirectoryException e)
        {
          logger.traceException(e);
        }
      }
    }
  }

  /**
   * Decompose an attribute value into a set of substring index keys.
   * The ID of the entry containing this value should be inserted
   * into the list of each of these keys.
   *
   * @param value A byte array containing the normalized attribute value
   * @param modifiedKeys The map into which the modified
   *  keys will be inserted.
   * @param insert <code>true</code> if generated keys should
   * be inserted or <code>false</code> otherwise.
   */
  private void substringKeys(byte[] value,
                             Map<byte[], Boolean> modifiedKeys,
                             Boolean insert)
  {
    byte[] keyBytes;

    // Example: The value is ABCDE and the substring length is 3.
    // We produce the keys ABC BCD CDE DE E
    // To find values containing a short substring such as DE,
    // iterate through keys with prefix DE. To find values
    // containing a longer substring such as BCDE, read keys
    // BCD and CDE.
    for (int i = 0, remain = value.length; remain > 0; i++, remain--)
    {
      int len = Math.min(substrLength, remain);
      keyBytes = makeSubstringKey(value, i, len);
      Boolean cInsert = modifiedKeys.get(keyBytes);
      if(cInsert == null)
      {
        modifiedKeys.put(keyBytes, insert);
      }
      else if(!cInsert.equals(insert))
      {
        modifiedKeys.remove(keyBytes);
      }
    }
  }

  /**
   * Return the substring length for an indexer.
   *
   * @return  The substring length configured for an sub string indexer.
   */
  public int getSubStringLen()
  {
    return substrLength;
  }
}
