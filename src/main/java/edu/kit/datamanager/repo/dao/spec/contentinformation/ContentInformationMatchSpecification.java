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
package edu.kit.datamanager.repo.dao.spec.contentinformation;

import edu.kit.datamanager.repo.domain.ContentInformation;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;

/**
 *
 * @author jejkal
 */
public class ContentInformationMatchSpecification{

  /**
   * Hidden constructor.
   */
  private ContentInformationMatchSpecification(){
  }

  public static Specification<ContentInformation> toSpecification(final String parentId, final String path, final boolean exactPath){
    return toSpecification(parentId, path, null, exactPath);
  }

  public static Specification<ContentInformation> toSpecification(final String parentId, final String path, String version, final boolean exactPath){
    return (Root<ContentInformation> root, CriteriaQuery<?> query, CriteriaBuilder builder) -> {
      query.distinct(true);

      Path<String> pid = root.get("parentResource").get("id");

      Predicate basePredicate;
      if(path != null && !exactPath){
        basePredicate = builder.and(builder.equal(pid, parentId), builder.like(root.get("relativePath"), "%" + path + "%"));

      } else if(path != null && exactPath){
        basePredicate = builder.and(builder.equal(pid, parentId), builder.equal(root.get("relativePath"), path));
      } else{
        //path is null, only query for parent
        basePredicate = builder.equal(pid, parentId);
      }
      if(version != null){
        return builder.and(basePredicate, builder.equal(root.get("version"), version));
      } else{
        return basePredicate;
      }
    };
  }
}
