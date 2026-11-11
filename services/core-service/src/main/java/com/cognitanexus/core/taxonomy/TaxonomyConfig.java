package com.cognitanexus.core.taxonomy;

import com.cognitanexus.core.taxonomy.domain.AcademicTaxonomy;
import com.cognitanexus.core.taxonomy.domain.TopicClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * 学术领域词表的装配。
 *
 * <p>词表在启动时加载一次并常驻：它不随请求变化，且每次分类都要遍历全部判别词，重复解析既无必要
 * 也会让单条分类的开销变成解析开销。加载失败直接让应用启动失败——见
 * {@link AcademicTaxonomy#fromJson} 的说明。
 */
@Configuration
public class TaxonomyConfig {

  @Bean
  public AcademicTaxonomy academicTaxonomy(
      ObjectMapper mapper,
      @Value("classpath:taxonomy/academic-fields.json") Resource taxonomyResource)
      throws IOException {
    try (InputStream in = taxonomyResource.getInputStream()) {
      return AcademicTaxonomy.load(in, mapper);
    }
  }

  @Bean
  public TopicClassifier topicClassifier(AcademicTaxonomy taxonomy) {
    return new TopicClassifier(taxonomy);
  }
}
