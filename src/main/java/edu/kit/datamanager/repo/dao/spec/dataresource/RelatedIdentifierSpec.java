/*
 * Copyright 2018 Karlsruhe Institute of Technology.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.kit.datamanager.repo.dao.spec.dataresource;

import edu.kit.datamanager.repo.domain.DataResource;
import edu.kit.datamanager.repo.domain.RelatedIdentifier;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.datacite.schema.kernel_4.Resource.RelatedIdentifiers;
import org.springframework.data.jpa.domain.Specification;

/**
 *
 * @author jejkal
 */
public class RelatedIdentifierSpec {

  /**
   * Hidden constructor.
   */
  private RelatedIdentifierSpec() {
  }

  /**
   * Search for values in all related identifiers.
   *
   * @param identifierValues Values to search for.
   * @return specification
   */
  public static Specification<DataResource> toSpecification(final String... identifierValues) {
    return toSpecification(null, identifierValues);
  }

  /**
   * Search for relation type in all related identifiers.
   *
   * @param relationType Relation type to search for.
   * @return specification
   */
  public static Specification<DataResource> toSpecification(final RelatedIdentifier.RELATION_TYPES relationType) {
    return toSpecification(relationType, (String[]) null);
  }

  /**
   * Search for relation type AND values in all related identifiers.
   *
   * @param relationType Relation type to search for.
   * @param identifierValues Values to search for.
   * @return specification
   */
  public static Specification<DataResource> toSpecification(final RelatedIdentifier.RELATION_TYPES relationType, final String... identifierValues) {
    Specification<DataResource> newSpec = Specification.where(null);
    if ((identifierValues == null || identifierValues.length == 0) && (relationType == null)) {
      return newSpec;
    }

    return (Root<DataResource> root, CriteriaQuery<?> query, CriteriaBuilder builder) -> {
      query.distinct(true);
      //join dataresource table with related identifiers table
      Join<DataResource, RelatedIdentifiers.RelatedIdentifier> altJoin = root.join("relatedIdentifiers", JoinType.INNER);
      //get all related identifiers of type relationType with one of the provided values
      Predicate allPredicates = null;
      Predicate predicateValue = null;
      Predicate predicateRelationType = null;
      int determineCase = 0; // 1 - identifer only, 2 - relationType only, 3 - both
      if (identifierValues != null && identifierValues.length != 0) {
        predicateValue = altJoin.get("value").in((Object[]) identifierValues);
        determineCase++;
      }
      if (relationType != null) {
        predicateRelationType = builder.equal(altJoin.get("relationType"), relationType);
        determineCase += 2;
      }

      switch (determineCase) {
        case 1:
          allPredicates = builder.and(predicateValue);
          break;
        case 2:
          allPredicates = builder.and(predicateRelationType);
          break;
        case 3:
          allPredicates = builder.and(predicateRelationType, predicateValue);
          break;
      }

      return allPredicates;
    };
  }
}
