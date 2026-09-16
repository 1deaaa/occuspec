package com.occuspec.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 体检对象表。 */
@TableName("persons")
public class Person {
  @TableId(type = IdType.ASSIGN_ID)
  private Long id;
  private String name;
  private String idCardHash;
  private String gender;
  private LocalDate birthDate;
  private String company;
  private String jobType;
  private String exposureHistory;
  private LocalDateTime createdAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getIdCardHash() { return idCardHash; }
  public void setIdCardHash(String idCardHash) { this.idCardHash = idCardHash; }
  public String getGender() { return gender; }
  public void setGender(String gender) { this.gender = gender; }
  public LocalDate getBirthDate() { return birthDate; }
  public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }
  public String getCompany() { return company; }
  public void setCompany(String company) { this.company = company; }
  public String getJobType() { return jobType; }
  public void setJobType(String jobType) { this.jobType = jobType; }
  public String getExposureHistory() { return exposureHistory; }
  public void setExposureHistory(String exposureHistory) { this.exposureHistory = exposureHistory; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
