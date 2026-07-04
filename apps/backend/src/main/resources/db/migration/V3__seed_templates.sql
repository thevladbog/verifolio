insert into template (type, locale, name, description, question_schema_json, output_schema_json, required_fields_json, verification_recommendations_json) values (
  'EMPLOYMENT_REFERENCE',
  'en',
  'Employment Reference',
  'Confirms professional experience and performance for a prospective employer or professional context.',
  '{"requesterQuestions":[{"key":"candidateRole","label":"What was the candidate''s role?","required":true,"publicDisplay":true},{"key":"workDates","label":"When did you work together?","required":true,"publicDisplay":true},{"key":"relationship","label":"What was your relationship?","required":true,"publicDisplay":true},{"key":"projects","label":"What projects did the candidate work on?","required":false,"publicDisplay":true},{"key":"responsibilities","label":"What were their main responsibilities?","required":false,"publicDisplay":true},{"key":"strengths","label":"What strengths did they demonstrate?","required":false,"publicDisplay":true},{"key":"wouldRecommend","label":"Would you recommend them for similar roles?","required":false,"publicDisplay":true}],"recommenderQuestions":[{"key":"relationship","label":"What was your relationship to the candidate?","required":true,"publicDisplay":true}]}'::jsonb,
  '{"sections":[{"key":"introduction","title":"Introduction"},{"key":"employmentDetails","title":"Employment Details"},{"key":"responsibilities","title":"Responsibilities and Projects"},{"key":"performance","title":"Performance and Strengths"},{"key":"recommendation","title":"Recommendation"}]}'::jsonb,
  '["candidateRole","workDates","relationship"]'::jsonb,
  '["CORPORATE_DOMAIN_CONFIRMED","RECOMMENDER_RELATIONSHIP_CONFIRMED","RECIPIENT_CONFIRMED"]'::jsonb
);

insert into template (type, locale, name, description, question_schema_json, output_schema_json, required_fields_json, verification_recommendations_json) values (
  'IMMIGRATION_REFERENCE',
  'en',
  'Immigration Reference',
  'Provides factual employment and professional experience confirmation for immigration authorities.',
  '{"requesterQuestions":[{"key":"exactEmploymentDates","label":"What were the exact employment dates?","required":true,"publicDisplay":true},{"key":"jobTitle","label":"What was the job title?","required":true,"publicDisplay":true},{"key":"employmentType","label":"Was the position full-time, part-time, or contract?","required":true,"publicDisplay":true},{"key":"mainDuties","label":"What were the main duties?","required":true,"publicDisplay":true},{"key":"technologiesUsed","label":"What technologies or tools were used?","required":false,"publicDisplay":true},{"key":"reportingLine","label":"What was the reporting line?","required":false,"publicDisplay":true},{"key":"projectsProducts","label":"What projects or products were worked on?","required":false,"publicDisplay":true},{"key":"companyContact","label":"What is the company contact information?","required":false,"publicDisplay":false},{"key":"letterheadAvailable","label":"Can this reference be printed on company letterhead?","required":false,"publicDisplay":true}],"recommenderQuestions":[{"key":"relationship","label":"What was your relationship to the employee?","required":true,"publicDisplay":true}]}'::jsonb,
  '{"sections":[{"key":"introduction","title":"Introduction"},{"key":"employmentDetails","title":"Employment Details"},{"key":"duties","title":"Duties and Responsibilities"},{"key":"technicalDetails","title":"Technical Details"},{"key":"confirmation","title":"Official Confirmation"}]}'::jsonb,
  '["exactEmploymentDates","jobTitle","employmentType","mainDuties"]'::jsonb,
  '["CORPORATE_DOMAIN_CONFIRMED","SCAN_ATTACHED","SIGNATURE_ATTACHED","SIGNATURE_VERIFIED","RECOMMENDER_RELATIONSHIP_CONFIRMED"]'::jsonb
);

insert into template (type, locale, name, description, question_schema_json, output_schema_json, required_fields_json, verification_recommendations_json) values (
  'VISA_SUPPORT_LETTER',
  'en',
  'Visa Support Letter',
  'Supports a professional or business visa application with an official letter from a sponsoring organization.',
  '{"requesterQuestions":[{"key":"purposeOfTravel","label":"What is the purpose of travel?","required":true,"publicDisplay":true},{"key":"travelDates","label":"What are the planned travel dates?","required":true,"publicDisplay":true},{"key":"applicantRole","label":"What is the applicant''s role or position?","required":true,"publicDisplay":true},{"key":"organizationRelationship","label":"What is the applicant''s relationship to the inviting or supporting organization?","required":true,"publicDisplay":true},{"key":"expenseCoverage","label":"Who covers the travel expenses?","required":false,"publicDisplay":true},{"key":"contactPerson","label":"Who is the contact person at the organization?","required":false,"publicDisplay":false},{"key":"letterheadAvailable","label":"Is company letterhead available for this letter?","required":false,"publicDisplay":true}],"recommenderQuestions":[{"key":"organizationName","label":"What is the name of the supporting organization?","required":true,"publicDisplay":true},{"key":"signatoryTitle","label":"What is your title and authority to issue this letter?","required":true,"publicDisplay":true}]}'::jsonb,
  '{"sections":[{"key":"introduction","title":"Introduction"},{"key":"travelDetails","title":"Travel Details"},{"key":"applicantDetails","title":"Applicant Details"},{"key":"supportStatement","title":"Support Statement"},{"key":"closingAndContact","title":"Closing and Contact Information"}]}'::jsonb,
  '["purposeOfTravel","travelDates","applicantRole","organizationRelationship"]'::jsonb,
  '["CORPORATE_DOMAIN_CONFIRMED","SCAN_ATTACHED","SIGNATURE_ATTACHED","SIGNATURE_VERIFIED"]'::jsonb
);

insert into template (type, locale, name, description, question_schema_json, output_schema_json, required_fields_json, verification_recommendations_json) values (
  'ACADEMIC_RECOMMENDATION',
  'en',
  'Academic Recommendation',
  'Supports a university, scholarship, fellowship, or academic program application.',
  '{"requesterQuestions":[{"key":"targetProgram","label":"What program or institution is the applicant applying to?","required":true,"publicDisplay":true}],"recommenderQuestions":[{"key":"knowledgeContext","label":"How do you know the applicant, and in what capacity?","required":true,"publicDisplay":true},{"key":"academicProfessionalQualities","label":"What academic or professional qualities stand out?","required":true,"publicDisplay":true},{"key":"analyticalExamples","label":"Can you give examples of the applicant''s analytical ability?","required":false,"publicDisplay":true},{"key":"disciplineExamples","label":"Can you give examples of the applicant''s discipline or independence?","required":false,"publicDisplay":true},{"key":"peerComparison","label":"How does the applicant compare with peers?","required":false,"publicDisplay":true},{"key":"programSuitability","label":"Why is the applicant suitable for the target program?","required":false,"publicDisplay":true}]}'::jsonb,
  '{"sections":[{"key":"introduction","title":"Introduction"},{"key":"academicQualities","title":"Academic and Professional Qualities"},{"key":"examples","title":"Examples and Evidence"},{"key":"peerComparison","title":"Comparison with Peers"},{"key":"recommendation","title":"Recommendation"}]}'::jsonb,
  '["targetProgram","knowledgeContext","academicProfessionalQualities"]'::jsonb,
  '["CORPORATE_DOMAIN_CONFIRMED","RECOMMENDER_RELATIONSHIP_CONFIRMED","SCAN_ATTACHED"]'::jsonb
);

insert into template (type, locale, name, description, question_schema_json, output_schema_json, required_fields_json, verification_recommendations_json) values (
  'CLIENT_TESTIMONIAL',
  'en',
  'Client Testimonial',
  'Confirms delivered work and client satisfaction for a freelancer, consultant, or service provider.',
  '{"requesterQuestions":[{"key":"projectScope","label":"What was the scope of the project?","required":true,"publicDisplay":true}],"recommenderQuestions":[{"key":"projectScope","label":"What was the scope of the project?","required":true,"publicDisplay":true},{"key":"deliveredResult","label":"What was the delivered result?","required":true,"publicDisplay":true},{"key":"workQuality","label":"How would you describe the quality of the work?","required":true,"publicDisplay":true},{"key":"communication","label":"How was the communication throughout the project?","required":false,"publicDisplay":true},{"key":"reliability","label":"How would you describe their reliability?","required":false,"publicDisplay":true},{"key":"deadlines","label":"Were deadlines met?","required":false,"publicDisplay":true},{"key":"wouldRecommendAgain","label":"Would you hire or recommend this person again?","required":false,"publicDisplay":true}]}'::jsonb,
  '{"sections":[{"key":"introduction","title":"Introduction"},{"key":"projectDetails","title":"Project Details"},{"key":"feedback","title":"Quality and Feedback"},{"key":"recommendation","title":"Recommendation"}]}'::jsonb,
  '["projectScope","deliveredResult","workQuality"]'::jsonb,
  '["EMAIL_CONFIRMED","CORPORATE_DOMAIN_CONFIRMED","RECOMMENDER_RELATIONSHIP_CONFIRMED"]'::jsonb
);

insert into template (type, locale, name, description, question_schema_json, output_schema_json, required_fields_json, verification_recommendations_json) values (
  'CHARACTER_REFERENCE',
  'en',
  'Character Reference',
  'Confirms personal reliability, trustworthiness, and reputation from someone who knows the person well.',
  '{"requesterQuestions":[{"key":"referenceContext","label":"In what context will this reference be used?","required":false,"publicDisplay":true}],"recommenderQuestions":[{"key":"acquaintanceDuration","label":"How long have you known this person?","required":true,"publicDisplay":true},{"key":"context","label":"In what context do you know them?","required":true,"publicDisplay":true},{"key":"confirmedQualities","label":"What qualities can you confirm about this person?","required":true,"publicDisplay":true},{"key":"responsibilityExamples","label":"Can you give examples of their responsibility or integrity?","required":false,"publicDisplay":true},{"key":"wouldRecommend","label":"Would you recommend this person?","required":false,"publicDisplay":true}]}'::jsonb,
  '{"sections":[{"key":"introduction","title":"Introduction"},{"key":"relationshipContext","title":"Relationship Context"},{"key":"characterAssessment","title":"Character Assessment"},{"key":"recommendation","title":"Recommendation"}]}'::jsonb,
  '["acquaintanceDuration","context","confirmedQualities"]'::jsonb,
  '["EMAIL_CONFIRMED","RECOMMENDER_RELATIONSHIP_CONFIRMED","RECIPIENT_CONFIRMED"]'::jsonb
);
