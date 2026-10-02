using System;
using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Infrastructure;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;
using Npgsql.EntityFrameworkCore.PostgreSQL.Metadata;
using ServicePult.Data;

#nullable disable

namespace ServicePult.Data.Migrations
{
    [DbContext(typeof(AppDbContext))]
    partial class AppDbContextModelSnapshot : ModelSnapshot
    {
        protected override void BuildModel(ModelBuilder modelBuilder)
        {
#pragma warning disable 612, 618
            modelBuilder
                .HasAnnotation("ProductVersion", "8.0.31")
                .HasAnnotation("Relational:MaxIdentifierLength", 63);

            NpgsqlModelBuilderExtensions.UseIdentityByDefaultColumns(modelBuilder);

            modelBuilder.Entity("ServicePult.Data.Act", b =>
                {
                    b.Property<Guid>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("uuid")
                        .HasColumnName("id");

                    b.Property<DateOnly>("ActDate")
                        .HasColumnType("date")
                        .HasColumnName("act_date");

                    b.Property<int>("EngineerId")
                        .HasColumnType("integer")
                        .HasColumnName("engineer_id");

                    b.Property<string>("Number")
                        .IsRequired()
                        .HasColumnType("char(11)")
                        .HasColumnName("number");

                    b.Property<string>("PdfFile")
                        .IsRequired()
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("pdf_file");

                    b.Property<int>("RequestId")
                        .HasColumnType("integer")
                        .HasColumnName("request_id");

                    b.Property<string>("Sha256")
                        .IsRequired()
                        .HasColumnType("char(64)")
                        .HasColumnName("sha256");

                    b.Property<short>("SignMode")
                        .HasColumnType("smallint")
                        .HasColumnName("sign_mode");

                    b.Property<string>("SignatureFile")
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("signature_file");

                    b.Property<DateTimeOffset?>("SignedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("signed_at");

                    b.Property<string>("SignerName")
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("signer_name");

                    b.Property<string>("SignerPosition")
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("signer_position");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.Property<int>("WorkMin")
                        .HasColumnType("integer")
                        .HasColumnName("work_min");

                    b.HasKey("Id");

                    b.HasIndex("EngineerId");

                    b.HasIndex("Number")
                        .IsUnique();

                    b.HasIndex("RequestId")
                        .IsUnique();

                    b.ToTable("act");
                });

            modelBuilder.Entity("ServicePult.Data.CalendarDay", b =>
                {
                    b.Property<DateOnly>("Day")
                        .HasColumnType("date")
                        .HasColumnName("day");

                    b.Property<bool>("IsWorking")
                        .HasColumnType("boolean")
                        .HasColumnName("is_working");

                    b.Property<string>("Note")
                        .HasMaxLength(60)
                        .HasColumnType("character varying(60)")
                        .HasColumnName("note");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Day");

                    b.ToTable("calendar_day");
                });

            modelBuilder.Entity("ServicePult.Data.ChecklistItem", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<bool>("NeedPhoto")
                        .HasColumnType("boolean")
                        .HasColumnName("need_photo");

                    b.Property<short>("Pos")
                        .HasColumnType("smallint")
                        .HasColumnName("pos");

                    b.Property<bool>("Required")
                        .HasColumnType("boolean")
                        .HasColumnName("required");

                    b.Property<string>("Text")
                        .IsRequired()
                        .HasMaxLength(200)
                        .HasColumnType("character varying(200)")
                        .HasColumnName("text");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.Property<int>("WorkTypeId")
                        .HasColumnType("integer")
                        .HasColumnName("work_type_id");

                    b.HasKey("Id");

                    b.HasIndex("WorkTypeId");

                    b.ToTable("checklist_item");
                });

            modelBuilder.Entity("ServicePult.Data.ChecklistResult", b =>
                {
                    b.Property<Guid>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("uuid")
                        .HasColumnName("id");

                    b.Property<int>("ChecklistItemId")
                        .HasColumnType("integer")
                        .HasColumnName("checklist_item_id");

                    b.Property<string>("Comment")
                        .HasMaxLength(300)
                        .HasColumnType("character varying(300)")
                        .HasColumnName("comment");

                    b.Property<bool>("Done")
                        .HasColumnType("boolean")
                        .HasColumnName("done");

                    b.Property<DateTimeOffset?>("DoneAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("done_at");

                    b.Property<int>("RequestId")
                        .HasColumnType("integer")
                        .HasColumnName("request_id");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("ChecklistItemId");

                    b.HasIndex("RequestId", "ChecklistItemId")
                        .IsUnique();

                    b.ToTable("checklist_result");
                });

            modelBuilder.Entity("ServicePult.Data.Contract", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<int>("CustomerId")
                        .HasColumnType("integer")
                        .HasColumnName("customer_id");

                    b.Property<DateOnly>("DateFrom")
                        .HasColumnType("date")
                        .HasColumnName("date_from");

                    b.Property<DateOnly?>("DateTo")
                        .HasColumnType("date")
                        .HasColumnName("date_to");

                    b.Property<bool>("ESignAllowed")
                        .HasColumnType("boolean")
                        .HasColumnName("e_sign_allowed");

                    b.Property<short>("MaxSla")
                        .HasColumnType("smallint")
                        .HasColumnName("max_sla");

                    b.Property<string>("Number")
                        .IsRequired()
                        .HasColumnType("char(11)")
                        .HasColumnName("number");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("CustomerId");

                    b.HasIndex("Number")
                        .IsUnique();

                    b.ToTable("contract");
                });

            modelBuilder.Entity("ServicePult.Data.Customer", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<string>("Code")
                        .IsRequired()
                        .HasColumnType("char(4)")
                        .HasColumnName("code");

                    b.Property<string>("Inn")
                        .IsRequired()
                        .HasMaxLength(12)
                        .HasColumnType("character varying(12)")
                        .HasColumnName("inn");

                    b.Property<string>("Name")
                        .IsRequired()
                        .HasMaxLength(200)
                        .HasColumnType("character varying(200)")
                        .HasColumnName("name");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("Code")
                        .IsUnique();

                    b.ToTable("customer");
                });

            modelBuilder.Entity("ServicePult.Data.Employee", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<bool>("Blocked")
                        .HasColumnType("boolean")
                        .HasColumnName("blocked");

                    b.Property<string>("FullName")
                        .IsRequired()
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("full_name");

                    b.Property<string>("Login")
                        .IsRequired()
                        .HasMaxLength(40)
                        .HasColumnType("character varying(40)")
                        .HasColumnName("login");

                    b.Property<string>("PassHash")
                        .IsRequired()
                        .HasMaxLength(128)
                        .HasColumnType("character varying(128)")
                        .HasColumnName("pass_hash");

                    b.Property<string>("Phone")
                        .HasMaxLength(20)
                        .HasColumnType("character varying(20)")
                        .HasColumnName("phone");

                    b.Property<string>("Position")
                        .IsRequired()
                        .HasMaxLength(60)
                        .HasColumnType("character varying(60)")
                        .HasColumnName("position");

                    b.Property<short>("Role")
                        .HasColumnType("smallint")
                        .HasColumnName("role");

                    b.Property<string>("TabNo")
                        .IsRequired()
                        .HasColumnType("char(5)")
                        .HasColumnName("tab_no");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("Login")
                        .IsUnique();

                    b.HasIndex("TabNo")
                        .IsUnique();

                    b.ToTable("employee");
                });

            modelBuilder.Entity("ServicePult.Data.Equipment", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<string>("InvCode")
                        .IsRequired()
                        .HasColumnType("char(12)")
                        .HasColumnName("inv_code");

                    b.Property<string>("Model")
                        .IsRequired()
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("model");

                    b.Property<string>("SerialNo")
                        .HasMaxLength(60)
                        .HasColumnType("character varying(60)")
                        .HasColumnName("serial_no");

                    b.Property<int>("SiteId")
                        .HasColumnType("integer")
                        .HasColumnName("site_id");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.Property<string>("Vendor")
                        .IsRequired()
                        .HasMaxLength(60)
                        .HasColumnType("character varying(60)")
                        .HasColumnName("vendor");

                    b.Property<DateOnly?>("WarrantyTo")
                        .HasColumnType("date")
                        .HasColumnName("warranty_to");

                    b.HasKey("Id");

                    b.HasIndex("InvCode")
                        .IsUnique();

                    b.HasIndex("SiteId");

                    b.ToTable("equipment");
                });

            modelBuilder.Entity("ServicePult.Data.Photo", b =>
                {
                    b.Property<Guid>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("uuid")
                        .HasColumnName("id");

                    b.Property<int?>("ChecklistItemId")
                        .HasColumnType("integer")
                        .HasColumnName("checklist_item_id");

                    b.Property<string>("FileName")
                        .IsRequired()
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("file_name");

                    b.Property<short>("Kind")
                        .HasColumnType("smallint")
                        .HasColumnName("kind");

                    b.Property<int>("RequestId")
                        .HasColumnType("integer")
                        .HasColumnName("request_id");

                    b.Property<string>("Sha256")
                        .IsRequired()
                        .HasColumnType("char(64)")
                        .HasColumnName("sha256");

                    b.Property<DateTimeOffset>("TakenAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("taken_at");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("ChecklistItemId");

                    b.HasIndex("RequestId");

                    b.ToTable("photo");
                });

            modelBuilder.Entity("ServicePult.Data.Request", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<DateTimeOffset?>("ArrivedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("arrived_at");

                    b.Property<string>("CancelReason")
                        .HasMaxLength(200)
                        .HasColumnType("character varying(200)")
                        .HasColumnName("cancel_reason");

                    b.Property<DateTimeOffset?>("ClosedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("closed_at");

                    b.Property<DateTimeOffset?>("CompletedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("completed_at");

                    b.Property<int>("ContractId")
                        .HasColumnType("integer")
                        .HasColumnName("contract_id");

                    b.Property<string>("Description")
                        .IsRequired()
                        .HasColumnType("text")
                        .HasColumnName("description");

                    b.Property<int>("DispatcherId")
                        .HasColumnType("integer")
                        .HasColumnName("dispatcher_id");

                    b.Property<int?>("EngineerId")
                        .HasColumnType("integer")
                        .HasColumnName("engineer_id");

                    b.Property<int?>("EquipmentId")
                        .HasColumnType("integer")
                        .HasColumnName("equipment_id");

                    b.Property<string>("Number")
                        .IsRequired()
                        .HasColumnType("char(11)")
                        .HasColumnName("number");

                    b.Property<int?>("ParentId")
                        .HasColumnType("integer")
                        .HasColumnName("parent_id");

                    b.Property<DateTimeOffset>("ReactionDue")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("reaction_due");

                    b.Property<DateTimeOffset>("RegisteredAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("registered_at");

                    b.Property<DateTimeOffset>("ResolutionDue")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("resolution_due");

                    b.Property<string>("ResultText")
                        .HasColumnType("text")
                        .HasColumnName("result_text");

                    b.Property<string>("ScannedCode")
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("scanned_code");

                    b.Property<int>("SiteId")
                        .HasColumnType("integer")
                        .HasColumnName("site_id");

                    b.Property<int>("SlaLevelId")
                        .HasColumnType("integer")
                        .HasColumnName("sla_level_id");

                    b.Property<short>("Status")
                        .HasColumnType("smallint")
                        .HasColumnName("status");

                    b.Property<bool>("TimeTrusted")
                        .HasColumnType("boolean")
                        .HasColumnName("time_trusted");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.Property<int>("WorkTypeId")
                        .HasColumnType("integer")
                        .HasColumnName("work_type_id");

                    b.HasKey("Id");

                    b.HasIndex("ContractId");

                    b.HasIndex("DispatcherId");

                    b.HasIndex("EngineerId");

                    b.HasIndex("EquipmentId");

                    b.HasIndex("Number")
                        .IsUnique();

                    b.HasIndex("ParentId");

                    b.HasIndex("SiteId");

                    b.HasIndex("SlaLevelId");

                    b.HasIndex("WorkTypeId");

                    b.ToTable("request");
                });

            modelBuilder.Entity("ServicePult.Data.Site", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<string>("Address")
                        .IsRequired()
                        .HasMaxLength(300)
                        .HasColumnType("character varying(300)")
                        .HasColumnName("address");

                    b.Property<string>("Code")
                        .IsRequired()
                        .HasColumnType("char(7)")
                        .HasColumnName("code");

                    b.Property<string>("ContactName")
                        .HasMaxLength(100)
                        .HasColumnType("character varying(100)")
                        .HasColumnName("contact_name");

                    b.Property<string>("ContactPhone")
                        .HasMaxLength(20)
                        .HasColumnType("character varying(20)")
                        .HasColumnName("contact_phone");

                    b.Property<int>("CustomerId")
                        .HasColumnType("integer")
                        .HasColumnName("customer_id");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("Code")
                        .IsUnique();

                    b.HasIndex("CustomerId");

                    b.ToTable("site");
                });

            modelBuilder.Entity("ServicePult.Data.SlaLevel", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<string>("Code")
                        .IsRequired()
                        .HasColumnType("char(5)")
                        .HasColumnName("code");

                    b.Property<short>("Mode")
                        .HasColumnType("smallint")
                        .HasColumnName("mode");

                    b.Property<string>("Name")
                        .IsRequired()
                        .HasMaxLength(40)
                        .HasColumnType("character varying(40)")
                        .HasColumnName("name");

                    b.Property<int>("ReactionMin")
                        .HasColumnType("integer")
                        .HasColumnName("reaction_min");

                    b.Property<int>("ResolutionMin")
                        .HasColumnType("integer")
                        .HasColumnName("resolution_min");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("Code")
                        .IsUnique();

                    b.ToTable("sla_level");
                });

            modelBuilder.Entity("ServicePult.Data.WorkLog", b =>
                {
                    b.Property<Guid>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("uuid")
                        .HasColumnName("id");

                    b.Property<int>("EmployeeId")
                        .HasColumnType("integer")
                        .HasColumnName("employee_id");

                    b.Property<DateTimeOffset?>("EndedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("ended_at");

                    b.Property<short>("Kind")
                        .HasColumnType("smallint")
                        .HasColumnName("kind");

                    b.Property<int>("RequestId")
                        .HasColumnType("integer")
                        .HasColumnName("request_id");

                    b.Property<DateTimeOffset>("StartedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("started_at");

                    b.Property<bool>("TimeTrusted")
                        .HasColumnType("boolean")
                        .HasColumnName("time_trusted");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("EmployeeId");

                    b.HasIndex("RequestId");

                    b.ToTable("work_log");
                });

            modelBuilder.Entity("ServicePult.Data.WorkType", b =>
                {
                    b.Property<int>("Id")
                        .ValueGeneratedOnAdd()
                        .HasColumnType("integer")
                        .HasColumnName("id");

                    NpgsqlPropertyBuilderExtensions.UseIdentityByDefaultColumn(b.Property<int>("Id"));

                    b.Property<string>("Code")
                        .IsRequired()
                        .HasColumnType("char(3)")
                        .HasColumnName("code");

                    b.Property<string>("Name")
                        .IsRequired()
                        .HasMaxLength(120)
                        .HasColumnType("character varying(120)")
                        .HasColumnName("name");

                    b.Property<int>("NormMin")
                        .HasColumnType("integer")
                        .HasColumnName("norm_min");

                    b.Property<DateTimeOffset>("UpdatedAt")
                        .HasColumnType("timestamp with time zone")
                        .HasColumnName("updated_at");

                    b.HasKey("Id");

                    b.HasIndex("Code")
                        .IsUnique();

                    b.ToTable("work_type");
                });

            modelBuilder.Entity("ServicePult.Data.Act", b =>
                {
                    b.HasOne("ServicePult.Data.Employee", "Engineer")
                        .WithMany()
                        .HasForeignKey("EngineerId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.HasOne("ServicePult.Data.Request", "Request")
                        .WithMany()
                        .HasForeignKey("RequestId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("Engineer");

                    b.Navigation("Request");
                });

            modelBuilder.Entity("ServicePult.Data.ChecklistItem", b =>
                {
                    b.HasOne("ServicePult.Data.WorkType", "WorkType")
                        .WithMany()
                        .HasForeignKey("WorkTypeId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("WorkType");
                });

            modelBuilder.Entity("ServicePult.Data.ChecklistResult", b =>
                {
                    b.HasOne("ServicePult.Data.ChecklistItem", "ChecklistItem")
                        .WithMany()
                        .HasForeignKey("ChecklistItemId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.HasOne("ServicePult.Data.Request", "Request")
                        .WithMany()
                        .HasForeignKey("RequestId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("ChecklistItem");

                    b.Navigation("Request");
                });

            modelBuilder.Entity("ServicePult.Data.Contract", b =>
                {
                    b.HasOne("ServicePult.Data.Customer", "Customer")
                        .WithMany()
                        .HasForeignKey("CustomerId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("Customer");
                });

            modelBuilder.Entity("ServicePult.Data.Equipment", b =>
                {
                    b.HasOne("ServicePult.Data.Site", "Site")
                        .WithMany()
                        .HasForeignKey("SiteId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("Site");
                });

            modelBuilder.Entity("ServicePult.Data.Photo", b =>
                {
                    b.HasOne("ServicePult.Data.ChecklistItem", "ChecklistItem")
                        .WithMany()
                        .HasForeignKey("ChecklistItemId")
                        .OnDelete(DeleteBehavior.Restrict);

                    b.HasOne("ServicePult.Data.Request", "Request")
                        .WithMany()
                        .HasForeignKey("RequestId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("ChecklistItem");

                    b.Navigation("Request");
                });

            modelBuilder.Entity("ServicePult.Data.Request", b =>
                {
                    b.HasOne("ServicePult.Data.Contract", "Contract")
                        .WithMany()
                        .HasForeignKey("ContractId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.HasOne("ServicePult.Data.Employee", "Dispatcher")
                        .WithMany()
                        .HasForeignKey("DispatcherId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.HasOne("ServicePult.Data.Employee", "Engineer")
                        .WithMany()
                        .HasForeignKey("EngineerId")
                        .OnDelete(DeleteBehavior.Restrict);

                    b.HasOne("ServicePult.Data.Equipment", "Equipment")
                        .WithMany()
                        .HasForeignKey("EquipmentId")
                        .OnDelete(DeleteBehavior.Restrict);

                    b.HasOne("ServicePult.Data.Request", "Parent")
                        .WithMany()
                        .HasForeignKey("ParentId")
                        .OnDelete(DeleteBehavior.Restrict);

                    b.HasOne("ServicePult.Data.Site", "Site")
                        .WithMany()
                        .HasForeignKey("SiteId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.HasOne("ServicePult.Data.SlaLevel", "SlaLevel")
                        .WithMany()
                        .HasForeignKey("SlaLevelId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.HasOne("ServicePult.Data.WorkType", "WorkType")
                        .WithMany()
                        .HasForeignKey("WorkTypeId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("Contract");

                    b.Navigation("Dispatcher");

                    b.Navigation("Engineer");

                    b.Navigation("Equipment");

                    b.Navigation("Parent");

                    b.Navigation("Site");

                    b.Navigation("SlaLevel");

                    b.Navigation("WorkType");
                });

            modelBuilder.Entity("ServicePult.Data.Site", b =>
                {
                    b.HasOne("ServicePult.Data.Customer", "Customer")
                        .WithMany()
                        .HasForeignKey("CustomerId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("Customer");
                });

            modelBuilder.Entity("ServicePult.Data.WorkLog", b =>
                {
                    b.HasOne("ServicePult.Data.Employee", "Employee")
                        .WithMany()
                        .HasForeignKey("EmployeeId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.HasOne("ServicePult.Data.Request", "Request")
                        .WithMany()
                        .HasForeignKey("RequestId")
                        .OnDelete(DeleteBehavior.Restrict)
                        .IsRequired();

                    b.Navigation("Employee");

                    b.Navigation("Request");
                });
#pragma warning restore 612, 618
        }
    }
}
